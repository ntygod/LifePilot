package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件移动工具 — 原子移动文件到目标路径，支持覆盖控制。
 *
 * <p>安全机制：
 * <ul>
 *   <li>RiskLevel HIGH — 每次执行需用户确认</li>
 *   <li>源路径通过 {@link PathSecurityChecker#check(Path)} 校验</li>
 *   <li>目标路径通过 {@link PathSecurityChecker#checkForWrite(Path)} 校验</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileMoveToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileMoveToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    public FileMoveToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /** 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。 */
    FileMoveToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 原子移动文件。
     *
     * @param input 工具输入，必需参数 source 和 destination，可选 overwrite
     * @return 包含 source 和 destination 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String sourceStr;
        String destStr;
        try {
            sourceStr = input.getParam("source", String.class);
            destStr = input.getParam("destination", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: " + e.getMessage());
        }

        boolean overwrite = input.getOptionalParam("overwrite", Boolean.class)
                .orElse(false);

        Path sourcePath = Path.of(sourceStr).toAbsolutePath().normalize();
        Path destPath = Path.of(destStr).toAbsolutePath().normalize();

        // 源路径安全检查
        var sourceRejection = securityChecker.check(sourcePath);
        if (sourceRejection.isPresent()) {
            return ToolResult.error(sourceRejection.get());
        }

        // 目标路径安全检查（写入场景）
        var destRejection = securityChecker.checkForWrite(destPath);
        if (destRejection.isPresent()) {
            return ToolResult.error(destRejection.get());
        }

        // 源文件存在性检查
        if (!Files.exists(sourcePath)) {
            return ToolResult.error("源文件不存在: " + sourceStr);
        }

        // 目标已存在且未指定覆盖
        if (Files.exists(destPath) && !overwrite) {
            return ToolResult.error("目标文件已存在，需指定 overwrite=true 才能覆盖: " + destStr);
        }

        try {
            // 确保目标父目录存在
            Path parentDir = destPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            if (overwrite) {
                Files.move(sourcePath, destPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(sourcePath, destPath, StandardCopyOption.ATOMIC_MOVE);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("source", sourcePath.toString());
            data.put("destination", destPath.toString());

            log.debug("文件移动成功: source={}, destination={}", sourceStr, destStr);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件移动失败: source={}, destination={}, error={}",
                    sourceStr, destStr, e.getMessage(), e);
            return ToolResult.error("文件移动失败: " + e.getMessage());
        }
    }
}
