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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件写入工具 — 原子写入（临时文件 + {@code Files.move(ATOMIC_MOVE)}）。
 *
 * <p>安全机制：
 * <ul>
 *   <li>RiskLevel MEDIUM — GuardrailEngine 自动执行 + 审计日志</li>
 *   <li>通过 {@link PathSecurityChecker} 校验路径白名单/黑名单</li>
 *   <li>原子写入防止写入中断导致文件损坏</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileWriteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileWriteToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    public FileWriteToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /**
     * 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。
     */
    FileWriteToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 原子写入文件。
     *
     * @param input 工具输入，必需参数 path 和 content，可选 createDirectories
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

        boolean createDirectories = input.getOptionalParam("createDirectories", Boolean.class)
                .orElse(true);

        Path filePath = Path.of(pathStr);

        // 路径安全检查（写入场景，文件可能不存在）
        var rejection = securityChecker.checkForWrite(filePath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        try {
            Path parentDir = filePath.toAbsolutePath().normalize().getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                if (createDirectories) {
                    Files.createDirectories(parentDir);
                } else {
                    return ToolResult.error("父目录不存在: " + parentDir);
                }
            }

            // 原子写入：先写临时文件，再 move
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Path tempFile = Files.createTempFile(parentDir, ".lifepilot-write-", ".tmp");
            try {
                Files.write(tempFile, bytes);
                Files.move(tempFile, filePath.toAbsolutePath().normalize(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 清理临时文件
                Files.deleteIfExists(tempFile);
                throw e;
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("path", filePath.toAbsolutePath().normalize().toString());
            data.put("bytesWritten", bytes.length);

            log.debug("文件写入成功: path={}, bytesWritten={}", pathStr, bytes.length);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件写入失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件写入失败: " + e.getMessage());
        }
    }
}
