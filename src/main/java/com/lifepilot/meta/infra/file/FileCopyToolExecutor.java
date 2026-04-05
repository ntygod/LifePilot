package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件复制工具 — 复制文件或目录到目标路径，支持覆盖控制和递归目录复制。
 *
 * <p>安全机制：
 * <ul>
 *   <li>源路径通过 {@link PathSecurityChecker#check(Path)} 校验</li>
 *   <li>目标路径通过 {@link PathSecurityChecker#checkForWrite(Path)} 校验</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileCopyToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileCopyToolExecutor.class);

    private final PathSecurityChecker securityChecker;

    public FileCopyToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /** 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。 */
    FileCopyToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 复制文件或目录。
     *
     * @param input 工具输入，必需参数 source 和 destination，可选 overwrite、recursive
     * @return 包含 source、destination 和 filesCopied 的结构化结果
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
        boolean recursive = input.getOptionalParam("recursive", Boolean.class)
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

        if (!Files.exists(sourcePath)) {
            return ToolResult.error("源路径不存在: " + sourceStr);
        }

        // 目录复制需要 recursive=true
        if (Files.isDirectory(sourcePath)) {
            if (!recursive) {
                return ToolResult.error("源路径是目录，需指定 recursive=true 才能递归复制: " + sourceStr);
            }
            return copyDirectory(sourcePath, destPath, overwrite, sourceStr, destStr);
        }

        // 单文件复制
        if (Files.exists(destPath) && !overwrite) {
            return ToolResult.error("目标文件已存在，需指定 overwrite=true 才能覆盖: " + destStr);
        }

        try {
            Path parentDir = destPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            if (overwrite) {
                Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(sourcePath, destPath);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("source", sourcePath.toString());
            data.put("destination", destPath.toString());
            data.put("filesCopied", 1);
            data.put("bytesWritten", Files.size(destPath));

            log.debug("文件复制成功: source={}, destination={}", sourceStr, destStr);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件复制失败: source={}, destination={}, error={}",
                    sourceStr, destStr, e.getMessage(), e);
            return ToolResult.error("文件复制失败: " + e.getMessage());
        }
    }

    /** 递归复制目录。 */
    private ToolResult copyDirectory(Path sourcePath, Path destPath, boolean overwrite,
                                     String sourceStr, String destStr) {
        // 防止将目录复制到自身内部，或复制到自身的祖先目录（可能覆盖源文件）
        if (destPath.startsWith(sourcePath) || sourcePath.startsWith(destPath)) {
            return ToolResult.error("源目录和目标目录不能互为父子关系: " + sourceStr + " → " + destStr);
        }

        try {
            int[] filesCopied = {0};

            Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path targetDir = destPath.resolve(sourcePath.relativize(dir));
                    if (!Files.exists(targetDir)) {
                        Files.createDirectories(targetDir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path targetFile = destPath.resolve(sourcePath.relativize(file));
                    if (overwrite) {
                        Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        if (Files.exists(targetFile)) {
                            throw new IOException("目标文件已存在: " + targetFile);
                        }
                        Files.copy(file, targetFile);
                    }
                    filesCopied[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });

            var data = new LinkedHashMap<String, Object>();
            data.put("source", sourcePath.toString());
            data.put("destination", destPath.toString());
            data.put("filesCopied", filesCopied[0]);

            log.debug("目录复制成功: source={}, destination={}, filesCopied={}",
                    sourceStr, destStr, filesCopied[0]);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("目录复制失败: source={}, destination={}, error={}",
                    sourceStr, destStr, e.getMessage(), e);
            return ToolResult.error("目录复制失败: " + e.getMessage());
        }
    }
}
