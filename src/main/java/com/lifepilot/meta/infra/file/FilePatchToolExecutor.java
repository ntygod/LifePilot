package com.lifepilot.meta.infra.file;

import com.lifepilot.config.path.PathResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件补丁工具 — 对文件执行行级或文本匹配的修改操作，原子写入。
 *
 * <p>支持两类操作模式：
 * <ul>
 *   <li>行级模式：insert / replace / delete — 按行号定位，自动按行号倒序执行避免漂移</li>
 *   <li>文本匹配模式：search_replace — 按文本内容定位，更鲁棒（不依赖行号）</li>
 * </ul>
 *
 * <p>安全机制：
 * <ul>
 *   <li>通过 {@link PathSecurityChecker#checkForWrite(Path)} 校验路径</li>
 *   <li>原子写入（临时文件 + {@code Files.move}）防止写入中断导致文件损坏</li>
 *   <li>行号从 1 开始，越界时返回错误</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FilePatchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FilePatchToolExecutor.class);

    private final PathSecurityChecker securityChecker;
    @Nullable
    private final FileEditHistory editHistory;
    @Nullable
    private final LintHookExecutor lintHook;
    @Nullable
    private final MetaProperties.Infra.FileEdit fileEditConfig;

    /**
     * 构造函数 — 共享 PathSecurityChecker 实例。
     *
     * @param securityChecker 路径安全检查器（共享）
     * @param editHistory     文件编辑历史（可为 null）
     * @param lintHook        lint 钩子执行器（可为 null）
     * @param fileEditConfig  文件编辑配置（可为 null）
     */
    FilePatchToolExecutor(PathSecurityChecker securityChecker,
                          @Nullable FileEditHistory editHistory,
                          @Nullable LintHookExecutor lintHook,
                          @Nullable MetaProperties.Infra.FileEdit fileEditConfig) {
        this.securityChecker = securityChecker;
        this.editHistory = editHistory;
        this.lintHook = lintHook;
        this.fileEditConfig = fileEditConfig;
    }

    /**
     * 对文件执行补丁操作。
     *
     * <p>行级操作（insert/replace/delete）自动按行号倒序执行，所有行号均基于原始文件，
     * 避免前面的操作导致后续行号漂移。</p>
     *
     * <p>文本匹配操作（search_replace）按给定顺序执行，通过精确文本定位。</p>
     *
     * @param input 工具输入，必需参数 path 和 operations
     * @return 包含 path 和 linesAffected 的结构化结果
     */
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInput input) {
        String pathStr;
        List<Map<String, Object>> operations;
        try {
            pathStr = input.getParam("path", String.class);
            operations = input.getParam("operations", List.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: " + e.getMessage());
        }
        pathStr = PathResolver.expand(pathStr);

        if (operations == null || operations.isEmpty()) {
            return ToolResult.error("operations 不能为空");
        }

        Path filePath = Path.of(pathStr).toAbsolutePath().normalize();

        // 路径安全检查（写入场景）
        var rejection = securityChecker.checkForWrite(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        if (!Files.exists(filePath)) {
            return ToolResult.error("文件不存在: " + pathStr);
        }
        if (!Files.isRegularFile(filePath)) {
            return ToolResult.error("路径不是普通文件: " + pathStr);
        }

        try {
            // 修改前捕获快照
            if (editHistory != null) {
                editHistory.captureBeforeModify(filePath);
            }

            // 读取全部内容
            String fullContent = Files.readString(filePath, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(fullContent.lines().toList());
            // 保留末尾空行：如果原始内容以换行结尾，lines() 会丢弃最后的空元素
            if (fullContent.endsWith("\n") || fullContent.endsWith("\r\n")) {
                lines.add("");
            }

            // 分离文本匹配操作和行级操作
            List<Map<String, Object>> searchReplaceOps = new ArrayList<>();
            List<Map<String, Object>> lineOps = new ArrayList<>();

            for (Map<String, Object> op : operations) {
                String type = (String) op.get("type");
                if (type == null) {
                    return ToolResult.error("operation 缺少 type 字段");
                }
                if ("search_replace".equals(type)) {
                    searchReplaceOps.add(op);
                } else {
                    lineOps.add(op);
                }
            }

            // 禁止同一请求中混用 search_replace 和行级操作 — 行号语义会冲突
            if (!searchReplaceOps.isEmpty() && !lineOps.isEmpty()) {
                return ToolResult.error("不能在同一请求中混用 search_replace 和行级操作（insert/replace/delete），"
                        + "请分两次调用");
            }

            int linesAffected = 0;

            // 1) 先执行 search_replace 操作（按给定顺序）
            for (Map<String, Object> op : searchReplaceOps) {
                var result = applySearchReplace(lines, op);
                if (result.error() != null) {
                    return ToolResult.error(result.error());
                }
                linesAffected += result.affected();
            }

            // 2) 再执行行级操作 — 按行号倒序排列，所有行号基于原始文件
            if (!lineOps.isEmpty()) {
                // 按主行号降序排列，同行号保持原始顺序的反转（倒序执行时恢复原始顺序）
                List<IndexedOp> indexed = new ArrayList<>(lineOps.size());
                for (int i = 0; i < lineOps.size(); i++) {
                    indexed.add(new IndexedOp(i, lineOps.get(i)));
                }
                indexed.sort(Comparator
                        .<IndexedOp, Integer>comparing(io -> {
                            Number n = (Number) io.op().get("line");
                            return n != null ? n.intValue() : 0;
                        })
                        .reversed()
                        .thenComparing(Comparator.<IndexedOp, Integer>comparing(IndexedOp::originalIndex).reversed()));

                for (IndexedOp io : indexed) {
                    var result = applyLineOp(lines, io.op());
                    if (result.error() != null) {
                        return ToolResult.error(result.error());
                    }
                    linesAffected += result.affected();
                }
            }

            // 原子写入：临时文件 + Files.move
            Path parentDir = filePath.getParent();
            Path tempFile = Files.createTempFile(parentDir, ".lifepilot-patch-", ".tmp");
            try {
                Files.write(tempFile, lines, StandardCharsets.UTF_8);
                Files.move(tempFile, filePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("path", filePath.toString());
            data.put("linesAffected", linesAffected);

            // 写入成功后执行 lint 检查
            if (lintHook != null && fileEditConfig != null && fileEditConfig.isAutoLint()) {
                String lintOutput = lintHook.runLint(filePath,
                        fileEditConfig.getLintCommands(),
                        fileEditConfig.getLintTimeoutSeconds());
                if (!lintOutput.isEmpty()) {
                    data.put("lintWarning", lintOutput);
                }
            }

            log.debug("文件补丁成功: path={}, linesAffected={}", pathStr, linesAffected);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件补丁失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件补丁失败: " + e.getMessage());
        }
    }

    /**
     * 执行文本匹配替换操作。
     *
     * <p>在文件内容中查找 oldText 并替换为 newText。仅替换首次出现，确保确定性。</p>
     */
    private OpResult applySearchReplace(List<String> lines, Map<String, Object> op) {
        String oldText = (String) op.get("oldText");
        String newText = (String) op.get("newText");

        if (oldText == null || oldText.isEmpty()) {
            return OpResult.fail("search_replace 操作缺少 oldText 字段");
        }
        if (newText == null) {
            return OpResult.fail("search_replace 操作缺少 newText 字段");
        }

        // 将 lines 合并为完整文本进行匹配
        String joined = String.join("\n", lines);
        int index = joined.indexOf(oldText);
        if (index < 0) {
            // 返回 oldText 的前 80 个字符作为提示
            String preview = oldText.length() > 80 ? oldText.substring(0, 80) + "..." : oldText;
            return OpResult.fail("search_replace 未找到匹配文本: \"" + preview + "\"");
        }

        String updated = joined.substring(0, index) + newText + joined.substring(index + oldText.length());
        lines.clear();
        lines.addAll(updated.lines().toList());
        // 保留末尾空行
        if (updated.endsWith("\n")) {
            lines.add("");
        }

        // 粗估影响行数：oldText 和 newText 的行数差
        int oldLines = (int) oldText.lines().count();
        int newLines = (int) newText.lines().count();
        return OpResult.ok(Math.max(oldLines, newLines));
    }

    /** 执行单个行级操作（insert/replace/delete）。 */
    private OpResult applyLineOp(List<String> lines, Map<String, Object> op) {
        String type = (String) op.get("type");

        Number lineNum = (Number) op.get("line");
        if (lineNum == null) {
            return OpResult.fail("operation 缺少 line 字段");
        }
        int line = lineNum.intValue();

        Number endLineNum = (Number) op.get("endLine");
        int endLine = endLineNum != null ? endLineNum.intValue() : line;

        String content = (String) op.get("content");

        return switch (type) {
            case "insert" -> {
                if (content == null) {
                    yield OpResult.fail("insert 操作缺少 content 字段");
                }
                if (line < 1 || line > lines.size() + 1) {
                    yield OpResult.fail("insert 行号越界: line=" + line
                            + ", 有效范围 [1, " + (lines.size() + 1) + "]");
                }
                String[] newLines = content.split("\n", -1);
                for (int i = 0; i < newLines.length; i++) {
                    lines.add(line - 1 + i, newLines[i]);
                }
                yield OpResult.ok(newLines.length);
            }
            case "replace" -> {
                if (content == null) {
                    yield OpResult.fail("replace 操作缺少 content 字段");
                }
                if (line < 1 || line > lines.size() || endLine < line || endLine > lines.size()) {
                    yield OpResult.fail("replace 行号越界: line=" + line
                            + ", endLine=" + endLine + ", 总行数=" + lines.size());
                }
                for (int i = endLine; i >= line; i--) {
                    lines.remove(i - 1);
                }
                String[] newLines = content.split("\n", -1);
                for (int i = 0; i < newLines.length; i++) {
                    lines.add(line - 1 + i, newLines[i]);
                }
                yield OpResult.ok(endLine - line + 1);
            }
            case "delete" -> {
                if (line < 1 || line > lines.size() || endLine < line || endLine > lines.size()) {
                    yield OpResult.fail("delete 行号越界: line=" + line
                            + ", endLine=" + endLine + ", 总行数=" + lines.size());
                }
                for (int i = endLine; i >= line; i--) {
                    lines.remove(i - 1);
                }
                yield OpResult.ok(endLine - line + 1);
            }
            default -> OpResult.fail("未知的操作类型: " + type
                    + "，支持 insert/replace/delete/search_replace");
        };
    }

    /** 带原始索引的操作包装，用于稳定排序。 */
    private record IndexedOp(int originalIndex, Map<String, Object> op) {}

    /** 操作执行结果 — 成功时 error 为 null，失败时 affected 无意义。 */
    private record OpResult(int affected, @Nullable String error) {
        static OpResult ok(int affected) { return new OpResult(affected, null); }
        static OpResult fail(String error) { return new OpResult(0, error); }
    }
}
