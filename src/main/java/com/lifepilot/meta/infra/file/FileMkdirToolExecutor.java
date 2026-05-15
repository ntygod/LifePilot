package com.lifepilot.meta.infra.file;

import com.lifepilot.config.path.PathResolver;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目录创建工具 — 创建目录，支持递归创建父目录。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker#checkForWrite(Path)} 校验路径。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class FileMkdirToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileMkdirToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    FileMkdirToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 创建目录。
     *
     * @param input 工具输入，必需参数 path
     * @return 包含 path 和 created 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: path");
        }
        pathStr = PathResolver.expand(pathStr);

        Path dirPath = Path.of(pathStr).toAbsolutePath().normalize();

        // 路径安全检查（写入场景）
        var rejection = securityChecker.checkForWrite(dirPath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        // 目录已存在时返回 created=false
        if (Files.exists(dirPath)) {
            if (Files.isDirectory(dirPath)) {
                var data = new LinkedHashMap<String, Object>();
                data.put("path", dirPath.toString());
                data.put("created", false);
                return ToolResult.success(Map.copyOf(data));
            }
            return ToolResult.error("路径已存在且不是目录: " + pathStr);
        }

        try {
            Files.createDirectories(dirPath);

            var data = new LinkedHashMap<String, Object>();
            data.put("path", dirPath.toString());
            data.put("created", true);

            log.debug("目录创建成功: path={}", pathStr);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("目录创建失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("目录创建失败: " + e.getMessage());
        }
    }
}
