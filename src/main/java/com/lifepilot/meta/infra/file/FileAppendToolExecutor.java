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
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件追加工具 — 向文件末尾追加内容，文件不存在时自动创建。
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileAppendToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileAppendToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    public FileAppendToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /** 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。 */
    FileAppendToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 追加内容到文件末尾。
     *
     * @param input 工具输入，必需参数 path 和 content
     * @return 包含 path 和 bytesWritten 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        String content;
        try {
            pathStr = input.getParam("path", String.class);
            content = input.getParam("content", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: " + e.getMessage());
        }

        Path filePath = Path.of(pathStr);

        // 路径安全检查（写入场景）
        var rejection = securityChecker.checkForWrite(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        try {
            // 确保父目录存在
            Path parentDir = filePath.toAbsolutePath().normalize().getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(filePath.toAbsolutePath().normalize(), bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            var data = new LinkedHashMap<String, Object>();
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("bytesWritten", bytes.length);

            log.debug("文件追加成功: path={}, bytesWritten={}", pathStr, bytes.length);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件追加失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件追加失败: " + e.getMessage());
        }
    }
}
