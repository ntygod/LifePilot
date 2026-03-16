package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件补丁工具 — 对文件执行行级 insert/replace/delete 操作，原子写入。
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

    public FilePatchToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /** 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。 */
    FilePatchToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 对文件执行行级补丁操作。
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
            // 读取全部行
            List<String> lines = new ArrayList<>(Files.readAllLines(filePath, StandardCharsets.UTF_8));
            int linesAffected = 0;

            // 按 operation 顺序执行
            for (Map<String, Object> op : operations) {
                String type = (String) op.get("type");
                if (type == null) {
                    return ToolResult.error("operation 缺少 type 字段");
                }

                Number lineNum = (Number) op.get("line");
                if (lineNum == null) {
                    return ToolResult.error("operation 缺少 line 字段");
                }
                int line = lineNum.intValue();

                Number endLineNum = (Number) op.get("endLine");
                int endLine = endLineNum != null ? endLineNum.intValue() : line;

                String content = (String) op.get("content");

                switch (type) {
                    case "insert" -> {
                        if (content == null) {
                            return ToolResult.error("insert 操作缺少 content 字段");
                        }
                        // insert 在指定行之前插入，行号范围 [1, lines.size()+1]
                        if (line < 1 || line > lines.size() + 1) {
                            return ToolResult.error("insert 行号越界: line=" + line
                                    + ", 有效范围 [1, " + (lines.size() + 1) + "]");
                        }
                        String[] newLines = content.split("\n", -1);
                        for (int i = 0; i < newLines.length; i++) {
                            lines.add(line - 1 + i, newLines[i]);
                        }
                        linesAffected += newLines.length;
                    }
                    case "replace" -> {
                        if (content == null) {
                            return ToolResult.error("replace 操作缺少 content 字段");
                        }
                        if (line < 1 || line > lines.size() || endLine < line || endLine > lines.size()) {
                            return ToolResult.error("replace 行号越界: line=" + line
                                    + ", endLine=" + endLine + ", 总行数=" + lines.size());
                        }
                        // 删除 [line, endLine] 范围的行
                        for (int i = endLine; i >= line; i--) {
                            lines.remove(i - 1);
                        }
                        // 在 line 位置插入新内容
                        String[] newLines = content.split("\n", -1);
                        for (int i = 0; i < newLines.length; i++) {
                            lines.add(line - 1 + i, newLines[i]);
                        }
                        linesAffected += (endLine - line + 1);
                    }
                    case "delete" -> {
                        if (line < 1 || line > lines.size() || endLine < line || endLine > lines.size()) {
                            return ToolResult.error("delete 行号越界: line=" + line
                                    + ", endLine=" + endLine + ", 总行数=" + lines.size());
                        }
                        for (int i = endLine; i >= line; i--) {
                            lines.remove(i - 1);
                        }
                        linesAffected += (endLine - line + 1);
                    }
                    default -> {
                        return ToolResult.error("未知的操作类型: " + type
                                + "，支持 insert/replace/delete");
                    }
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

            log.debug("文件补丁成功: path={}, linesAffected={}", pathStr, linesAffected);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件补丁失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件补丁失败: " + e.getMessage());
        }
    }
}
