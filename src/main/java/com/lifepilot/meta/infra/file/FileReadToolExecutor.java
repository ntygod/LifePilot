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
import java.util.LinkedHashMap;
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
                .map(value -> Math.max(1, value))
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
            var sb = new StringBuilder();
            boolean truncated = false;
            int totalLines = 0;
            int lastIncludedLine = 0;
            int requestedStart = startLineOpt.map(value -> Math.max(1, value)).orElse(1);
            int requestedEnd = endLineOpt
                    .map(value -> Math.max(requestedStart, value))
                    .orElse(Integer.MAX_VALUE);

            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    int lineNumber = totalLines;
                    if (lineNumber < requestedStart || lineNumber > requestedEnd) {
                        continue;
                    }
                    if (truncated) {
                        continue;
                    }

                    int projectedLength = sb.length() + line.length() + (sb.isEmpty() ? 0 : 1);
                    if (sb.isEmpty() && line.length() > maxChars) {
                        sb.append(line, 0, maxChars);
                        truncated = true;
                        lastIncludedLine = lineNumber;
                        continue;
                    }
                    if (!sb.isEmpty() && projectedLength > maxChars) {
                        truncated = true;
                        continue;
                    }
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(line);
                    lastIncludedLine = lineNumber;
                }
            }

            String content = sb.toString();
            int actualStart = totalLines == 0 ? 1 : Math.min(requestedStart, totalLines);
            int clampedEnd = totalLines == 0
                    ? 0
                    : Math.max(actualStart, Math.min(requestedEnd == Integer.MAX_VALUE ? totalLines : requestedEnd, totalLines));
            int actualEnd = truncated && lastIncludedLine > 0 ? lastIncludedLine : clampedEnd;

            if (truncated) {
                content += "[文件已截断]";
                content += "\n...[内容已截断，maxChars=" + maxChars
                        + "，显示行 " + actualStart + "-" + actualEnd + "/" + totalLines + "]";
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", content);
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("size", fileSize);
            data.put("totalLines", totalLines);
            data.put("truncated", truncated);
            if (startLineOpt.isPresent() || endLineOpt.isPresent()) {
                data.put("startLine", actualStart);
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
