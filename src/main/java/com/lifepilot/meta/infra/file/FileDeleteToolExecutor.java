package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 文件删除工具 — 删除文件或目录，支持递归删除。
 *
 * <p>安全机制：
 * <ul>
 *   <li>RiskLevel HIGH — 每次执行需用户确认</li>
 *   <li>通过 {@link PathSecurityChecker} 校验路径白名单/黑名单</li>
 *   <li>非空目录默认拒绝删除，需显式指定 recursive=true</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileDeleteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileDeleteToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    public FileDeleteToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /** 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。 */
    FileDeleteToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 删除文件或目录。
     *
     * @param input 工具输入，必需参数 path，可选 recursive
     * @return 包含 path 和 deleted 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: path");
        }

        boolean recursive = input.getOptionalParam("recursive", Boolean.class)
                .orElse(false);

        Path targetPath = Path.of(pathStr).toAbsolutePath().normalize();

        // 路径安全检查
        var rejection = securityChecker.check(targetPath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        // 文件不存在时返回 deleted=false
        if (!Files.exists(targetPath)) {
            var data = new LinkedHashMap<String, Object>();
            data.put("path", targetPath.toString());
            data.put("deleted", false);
            return ToolResult.success(Map.copyOf(data));
        }

        try {
            if (Files.isDirectory(targetPath)) {
                if (!recursive) {
                    // 检查目录是否为空
                    try (Stream<Path> entries = Files.list(targetPath)) {
                        if (entries.findFirst().isPresent()) {
                            return ToolResult.error("目录非空，需指定 recursive=true 才能删除: " + pathStr);
                        }
                    }
                    // 空目录直接删除
                    Files.delete(targetPath);
                } else {
                    // 递归删除：逆序遍历（先删文件再删目录）
                    try (Stream<Path> walk = Files.walk(targetPath)) {
                        walk.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.delete(p);
                                    } catch (IOException e) {
                                        throw new RuntimeException("删除失败: " + p, e);
                                    }
                                });
                    }
                }
            } else {
                Files.delete(targetPath);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("path", targetPath.toString());
            data.put("deleted", true);

            log.debug("文件删除成功: path={}, recursive={}", pathStr, recursive);
            return ToolResult.success(Map.copyOf(data));

        } catch (RuntimeException e) {
            // 递归删除中的 RuntimeException 包装了 IOException
            log.error("文件删除失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件删除失败: " + e.getMessage());
        } catch (IOException e) {
            log.error("文件删除失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件删除失败: " + e.getMessage());
        }
    }
}
