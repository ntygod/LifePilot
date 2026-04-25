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
import java.util.PriorityQueue;
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
    private static final Comparator<EntryCandidate> ENTRY_ORDER = Comparator
            .<EntryCandidate, Boolean>comparing(candidate -> !candidate.directory())
            .thenComparing(EntryCandidate::relativeName);

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
        pathStr = PathExpander.expand(pathStr);

        int maxDepth = input.getOptionalParam("maxDepth", Number.class)
                .map(Number::intValue)
                .orElse(3);
        String pattern = input.getOptionalParam("pattern", String.class)
                .orElse(null);
        int maxEntries = input.getOptionalParam("maxEntries", Number.class)
                .map(Number::intValue)
                .map(value -> Math.max(0, value))
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

            int totalEntries = 0;
            PriorityQueue<EntryCandidate> selectedEntries = new PriorityQueue<>(ENTRY_ORDER.reversed());
            try (Stream<Path> walk = Files.walk(dirPath, maxDepth)) {
                for (var iterator = walk
                        .filter(p -> !p.equals(dirPath))
                        .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                        .iterator(); iterator.hasNext(); ) {
                    Path path = iterator.next();
                    totalEntries++;
                    EntryCandidate candidate = buildCandidate(dirPath, path);
                    if (maxEntries == 0) {
                        continue;
                    }
                    if (selectedEntries.size() < maxEntries) {
                        selectedEntries.offer(candidate);
                        continue;
                    }
                    EntryCandidate worst = selectedEntries.peek();
                    if (worst != null && ENTRY_ORDER.compare(candidate, worst) < 0) {
                        selectedEntries.poll();
                        selectedEntries.offer(candidate);
                    }
                }
            }

            boolean truncated = totalEntries > maxEntries;
            List<EntryCandidate> orderedEntries = selectedEntries.stream()
                    .sorted(ENTRY_ORDER)
                    .toList();
            List<Map<String, Object>> entries = new ArrayList<>(orderedEntries.size());
            for (EntryCandidate candidate : orderedEntries) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", candidate.relativeName());
                entry.put("type", candidate.directory() ? "directory" : "file");
                if (candidate.size() != null) {
                    entry.put("size", candidate.size());
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

    private EntryCandidate buildCandidate(Path rootDir, Path path) {
        boolean directory = Files.isDirectory(path);
        Long size = null;
        if (!directory) {
            try {
                size = Files.size(path);
            } catch (IOException ignored) {
                // 无法获取大小时保留 null
            }
        }
        return new EntryCandidate(rootDir.relativize(path).toString(), directory, size);
    }

    private record EntryCandidate(String relativeName, boolean directory, Long size) {}
}
