package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 文件列表工具 — 列出目录内容，支持深度限制、glob 过滤、maxEntries 截断和目录优先排序。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker} 校验路径白名单/黑名单。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileListToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileListToolExecutor.class);

    private final PathSecurityChecker securityChecker;
    private final int defaultMaxEntries;

    public FileListToolExecutor(MetaProperties properties) {
        var fileConfig = properties.getInfra().getFile();
        this.securityChecker = new PathSecurityChecker(fileConfig);
        this.defaultMaxEntries = fileConfig.getDefaultMaxEntries();
    }

    /**
     * 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。
     */
    FileListToolExecutor(PathSecurityChecker securityChecker, int defaultMaxEntries) {
        this.securityChecker = securityChecker;
        this.defaultMaxEntries = defaultMaxEntries;
    }

    /**
     * 列出目录内容，支持 maxEntries 截断和目录优先排序。
     *
     * @param input 工具输入，必需参数 path，可选 maxDepth、pattern、maxEntries
     * @return 包含 path、entries、truncated、totalEntries 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: path");
        }

        int maxDepth = input.getOptionalParam("maxDepth", Number.class)
                .map(Number::intValue)
                .orElse(3);
        String pattern = input.getOptionalParam("pattern", String.class)
                .orElse(null);
        int maxEntries = input.getOptionalParam("maxEntries", Number.class)
                .map(Number::intValue)
                .orElse(defaultMaxEntries);

        Path dirPath = Path.of(pathStr);

        var rejection = securityChecker.check(dirPath);
        if (rejection.isPresent()) {
            return ToolResult.error(rejection.get());
        }

        if (!Files.exists(dirPath)) {
            return ToolResult.error("目录不存在: " + pathStr);
        }
        if (!Files.isDirectory(dirPath)) {
            return ToolResult.error("路径不是目录: " + pathStr);
        }

        try {
            PathMatcher matcher = pattern != null
                    ? FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                    : null;

            // 收集所有条目，目录优先排序
            List<Path> allPaths;
            try (Stream<Path> walk = Files.walk(dirPath, maxDepth)) {
                allPaths = walk
                        .filter(p -> !p.equals(dirPath))
                        .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                        .sorted(Comparator
                                .<Path, Boolean>comparing(p -> !Files.isDirectory(p))
                                .thenComparing(p -> dirPath.relativize(p).toString()))
                        .toList();
            }

            int totalEntries = allPaths.size();
            boolean truncated = totalEntries > maxEntries;

            List<Map<String, Object>> entries = new ArrayList<>();
            int limit = Math.min(totalEntries, maxEntries);
            for (int i = 0; i < limit; i++) {
                Path p = allPaths.get(i);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", dirPath.relativize(p).toString());
                entry.put("type", Files.isDirectory(p) ? "directory" : "file");
                try {
                    if (Files.isRegularFile(p)) {
                        entry.put("size", Files.size(p));
                    }
                } catch (IOException ignored) {
                    // 无法获取大小时跳过
                }
                entries.add(entry);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("path", dirPath.toAbsolutePath().normalize().toString());
            data.put("entries", entries);
            data.put("totalEntries", totalEntries);
            data.put("truncated", truncated);

            log.debug("目录列表成功: path={}, totalEntries={}, truncated={}",
                    pathStr, totalEntries, truncated);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("目录列表失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("目录列表失败: " + e.getMessage());
        }
    }
}
