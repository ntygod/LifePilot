package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件读取工具 — 读取文件内容，支持编码检测和大文件截断。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker} 校验路径白名单/黑名单。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileReadToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileReadToolExecutor.class);

    private final PathSecurityChecker securityChecker;
    private final int maxReadSize;

    public FileReadToolExecutor(MetaProperties properties) {
        var fileConfig = properties.getInfra().getFile();
        this.securityChecker = new PathSecurityChecker(fileConfig);
        this.maxReadSize = fileConfig.getMaxReadSize();
    }

    /**
     * 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。
     */
    FileReadToolExecutor(PathSecurityChecker securityChecker, int maxReadSize) {
        this.securityChecker = securityChecker;
        this.maxReadSize = maxReadSize;
    }

    /**
     * 读取文件内容。
     *
     * @param input 工具输入，必需参数 path，可选 encoding
     * @return 包含 content、path、size、truncated 的结构化结果
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

        Path filePath = Path.of(pathStr);

        // 路径安全检查
        var rejection = securityChecker.check(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        // 文件存在性检查
        if (!Files.exists(filePath)) {
            return ToolResult.error("文件不存在: " + pathStr);
        }
        if (!Files.isRegularFile(filePath)) {
            return ToolResult.error("路径不是普通文件: " + pathStr);
        }

        try {
            Charset charset = Charset.forName(encoding);
            long fileSize = Files.size(filePath);
            boolean truncated = fileSize > maxReadSize;

            String content;
            if (truncated) {
                // 读取截断大小的字节后转换为字符串
                byte[] bytes = new byte[maxReadSize];
                try (var is = Files.newInputStream(filePath)) {
                    int read = is.read(bytes);
                    content = new String(bytes, 0, read, charset);
                }
                content += "\n...[文件已截断，原始大小: " + fileSize + " 字节，最大读取: " + maxReadSize + " 字节]";
            } else {
                content = Files.readString(filePath, charset);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("content", content);
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("size", fileSize);
            data.put("truncated", truncated);

            log.debug("文件读取成功: path={}, size={}, truncated={}", pathStr, fileSize, truncated);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件读取失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件读取失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("不支持的编码: " + encoding);
        }
    }
}
