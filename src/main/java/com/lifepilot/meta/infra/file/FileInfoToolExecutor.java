package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件信息工具 — 查询文件或目录的元数据信息。
 *
 * <p>返回路径、大小、最后修改时间、类型、权限和 MIME 类型等信息。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileInfoToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileInfoToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    public FileInfoToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /** 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。 */
    FileInfoToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 查询文件元数据。
     *
     * @param input 工具输入，必需参数 path
     * @return 包含文件元数据的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: path");
        }

        Path filePath = Path.of(pathStr).toAbsolutePath().normalize();

        // 路径安全检查
        var rejection = securityChecker.check(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        if (!Files.exists(filePath)) {
            return ToolResult.error("文件不存在: " + pathStr);
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

            // MIME 类型检测
            String mimeType = null;
            if (!attrs.isDirectory()) {
                try {
                    mimeType = Files.probeContentType(filePath);
                } catch (IOException ignored) {
                    // probeContentType 失败时使用默认值
                }
            }
            if (mimeType == null && !attrs.isDirectory()) {
                mimeType = "application/octet-stream";
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("path", filePath.toString());
            data.put("size", attrs.size());
            data.put("lastModified", Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString());
            data.put("isDirectory", attrs.isDirectory());
            data.put("isReadable", Files.isReadable(filePath));
            data.put("isWritable", Files.isWritable(filePath));
            if (mimeType != null) {
                data.put("mimeType", mimeType);
            }

            log.debug("文件信息查询成功: path={}", pathStr);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件信息查询失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件信息查询失败: " + e.getMessage());
        }
    }
}
