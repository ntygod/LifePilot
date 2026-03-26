package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件读取工具 — 读取文件内容，支持行范围、maxChars 截断和编码检测。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker} 校验路径白名单/黑名单。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileReadToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileReadToolExecutor.class);

    private final PathSecurityChecker securityChecker;
    private final int defaultMaxChars;

    public FileReadToolExecutor(MetaProperties properties) {
        var fileConfig = properties.getInfra().getFile();
        this.securityChecker = new PathSecurityChecker(fileConfig);
        this.defaultMaxChars = fileConfig.getDefaultMaxChars();
    }

    /**
     * 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。
     */
    FileReadToolExecutor(PathSecurityChecker securityChecker, int defaultMaxChars) {
        this.securityChecker = securityChecker;
        this.defaultMaxChars = defaultMaxChars;
    }

    /**
     * 读取文件内容，支持行范围和 maxChars 截断。
     *
     * @param input 工具输入，必需参数 path，可选 encoding、startLine、endLine、maxChars
     * @return 包含 content、path、size、truncated、totalLines 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: path");
        }

        String encoding = input.getOptionalParam("encoding", String.class)
                .orElse("UTF-8");
        var startLineOpt = input.getOptionalParam("startLine", Number.class)
                .map(Number::intValue);
        var endLineOpt = input.getOptionalParam("endLine", Number.class)
                .map(Number::intValue);
        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue)
                .orElse(defaultMaxChars);

        Path filePath = Path.of(pathStr);

        // 路径安全检查
        var rejection = securityChecker.check(filePath);
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
            Charset charset = Charset.forName(encoding);
            long fileSize = Files.size(filePath);

            // 使用 BufferedReader 逐行读取，避免大文件预分配
            List<String> allLines = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    allLines.add(line);
                }
            }

            int totalLines = allLines.size();

            // 确定行范围（1-based，自动调整到有效范围）
            int start = startLineOpt.map(s -> Math.max(1, Math.min(s, totalLines))).orElse(1);
            int end = endLineOpt.map(e -> Math.max(start, Math.min(e, totalLines))).orElse(totalLines);

            // 提取指定范围的行，按 maxChars 截断（按完整行）
            var sb = new StringBuilder();
            boolean truncated = false;
            int actualEnd = start - 1;

            for (int i = start - 1; i < end; i++) {
                String line = allLines.get(i);
                int lineLen = line.length() + 1; // +1 for newline
                if (sb.length() == 0 && line.length() > maxChars) {
                    sb.append(line, 0, maxChars);
                    truncated = true;
                    actualEnd = i + 1;
                    break;
                }
                if (sb.length() + lineLen > maxChars && sb.length() > 0) {
                    truncated = true;
                    break;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
                actualEnd = i + 1;
            }

            String content = sb.toString();
            if (truncated) {
                content += "[文件已截断]";
                content += "\n...[内容已截断，maxChars=" + maxChars
                        + "，显示行 " + start + "-" + actualEnd + "/" + totalLines + "]";
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", content);
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("size", fileSize);
            data.put("totalLines", totalLines);
            data.put("truncated", truncated);
            if (startLineOpt.isPresent() || endLineOpt.isPresent()) {
                data.put("startLine", start);
                data.put("endLine", actualEnd);
            }

            log.debug("文件读取成功: path={}, size={}, totalLines={}, truncated={}",
                    pathStr, fileSize, totalLines, truncated);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件读取失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件读取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("不支持的编码: " + encoding);
        }
    }
}
