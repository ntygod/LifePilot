package com.lifepilot.meta.infra.file;

import com.lifepilot.config.path.PathResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 文件搜索工具 — 递归搜索文件内容，支持正则、glob 过滤、深度限制、上下文行和二进制检测。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker} 校验路径白名单/黑名单。</p>
 *
 * <p>性能优化：收集够 maxResults 条匹配后，额外扫描有限文件获取 totalMatches 估算值，
 * 而非遍历全部文件精确计数。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileSearchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileSearchToolExecutor.class);
    private static final int BINARY_CHECK_SIZE = 512;

    /** 结果满后额外扫描的文件数上限，用于估算 totalEstimate。 */
    private static final int EXTRA_SCAN_FILES = 20;

    private final PathSecurityChecker securityChecker;

    public FileSearchToolExecutor(MetaProperties properties) {
        this.securityChecker = new PathSecurityChecker(properties.getInfra().getFile());
    }

    /**
     * 构造函数 — 允许注入自定义 PathSecurityChecker（用于测试）。
     */
    FileSearchToolExecutor(PathSecurityChecker securityChecker) {
        this.securityChecker = securityChecker;
    }

    /**
     * 递归搜索文件内容。
     *
     * @param input 工具输入，必需参数 path 和 pattern，可选 filePattern、maxResults、offset、maxDepth、contextLines
     * @return 包含 matches、totalMatches、hasMore 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String pathStr;
        String patternStr;
        try {
            pathStr = input.getParam("path", String.class);
            patternStr = input.getParam("pattern", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: " + e.getMessage());
        }
        pathStr = PathResolver.expand(pathStr);

        String filePattern = input.getOptionalParam("filePattern", String.class)
                .orElse(null);
        int maxResults = input.getOptionalParam("maxResults", Number.class)
                .map(Number::intValue)
                .map(v -> Math.max(1, v))
                .orElse(50);
        int offset = input.getOptionalParam("offset", Number.class)
                .map(Number::intValue)
                .map(o -> Math.max(o, 0))
                .orElse(0);
        int maxDepth = input.getOptionalParam("maxDepth", Number.class)
                .map(Number::intValue)
                .map(d -> Math.max(1, d))
                .orElse(Integer.MAX_VALUE);
        int contextLines = input.getOptionalParam("contextLines", Number.class)
                .map(Number::intValue)
                .orElse(0);

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

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("无效的正则表达式: " + e.getMessage());
        }

        PathMatcher fileMatcher = filePattern != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + filePattern)
                : null;

        try {
            List<Map<String, Object>> matches = new ArrayList<>();
            int totalMatches = 0;
            int skipped = 0;
            int extraFilesScanned = 0;
            boolean resultsFull = false;

            try (Stream<Path> walk = Files.walk(dirPath, maxDepth)) {
                var files = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> fileMatcher == null || fileMatcher.matches(p.getFileName()));

                for (Iterator<Path> iterator = files.iterator(); iterator.hasNext(); ) {
                    // 提前终止：结果已满且额外扫描文件数已达上限
                    if (resultsFull && extraFilesScanned >= EXTRA_SCAN_FILES) {
                        break;
                    }

                    Path file = iterator.next();
                    if (isBinaryFile(file)) {
                        log.debug("跳过二进制文件: path={}", file);
                        continue;
                    }

                    if (resultsFull) {
                        extraFilesScanned++;
                    }

                    SearchStats stats = searchInFile(
                            dirPath, file, regex, contextLines,
                            offset, maxResults,
                            matches, totalMatches, skipped
                    );
                    totalMatches = stats.totalMatches();
                    skipped = stats.skipped();

                    if (!resultsFull && matches.size() >= maxResults && skipped >= offset) {
                        resultsFull = true;
                    }
                }
            }

            freezeContextLists(matches);

            var data = new LinkedHashMap<String, Object>();
            data.put("matches", matches);
            data.put("totalMatches", totalMatches);
            data.put("hasMore", totalMatches > offset + matches.size());

            log.debug("文件搜索完成: path={}, pattern={}, totalMatches={}", pathStr, patternStr, totalMatches);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件搜索失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件搜索失败: " + e.getMessage());
        }
    }

    private SearchStats searchInFile(Path rootDir,
                                     Path file,
                                     Pattern regex,
                                     int contextLines,
                                     int offset,
                                     int maxResults,
                                     List<Map<String, Object>> matches,
                                     int totalMatches,
                                     int skipped) {
        ArrayDeque<String> previousLines = contextLines > 0 ? new ArrayDeque<>(contextLines) : null;
        List<PendingAfterContext> pendingMatches = contextLines > 0 ? new ArrayList<>() : List.of();

        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (contextLines > 0) {
                    for (Iterator<PendingAfterContext> iterator = pendingMatches.iterator(); iterator.hasNext(); ) {
                        PendingAfterContext pending = iterator.next();
                        pending.afterContext().add(line);
                        pending.remainingAfterLines--;
                        if (pending.remainingAfterLines <= 0) {
                            iterator.remove();
                        }
                    }
                }

                if (regex.matcher(line).find()) {
                    totalMatches++;
                    if (skipped < offset) {
                        skipped++;
                    } else if (matches.size() < maxResults) {
                        var match = new LinkedHashMap<String, Object>();
                        match.put("file", rootDir.relativize(file).toString());
                        match.put("line", lineNumber);
                        match.put("content", line);

                        if (contextLines > 0 && previousLines != null) {
                            match.put("beforeContext", List.copyOf(previousLines));
                            var afterContext = new ArrayList<String>();
                            match.put("afterContext", afterContext);
                            pendingMatches.add(new PendingAfterContext(afterContext, contextLines));
                        }

                        matches.add(match);
                    }
                }

                if (contextLines > 0 && previousLines != null) {
                    if (previousLines.size() == contextLines) {
                        previousLines.removeFirst();
                    }
                    previousLines.addLast(line);
                }
            }
        } catch (IOException e) {
            log.debug("跳过无法读取的文件: path={}, error={}", file, e.getMessage());
        }

        return new SearchStats(totalMatches, skipped);
    }

    @SuppressWarnings("unchecked")
    private void freezeContextLists(List<Map<String, Object>> matches) {
        for (Map<String, Object> match : matches) {
            Object afterContext = match.get("afterContext");
            if (afterContext instanceof List<?> list) {
                match.put("afterContext", List.copyOf((List<String>) list));
            }
        }
    }

    /**
     * 检测文件是否为二进制文件 — 读取前 512 字节，检查是否包含 NUL 字节。
     */
    private boolean isBinaryFile(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[BINARY_CHECK_SIZE];
            int read = is.read(buffer);
            if (read <= 0) {
                return false;
            }
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0x00) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private record SearchStats(int totalMatches, int skipped) {}

    private static final class PendingAfterContext {
        private final List<String> afterContext;
        private int remainingAfterLines;

        private PendingAfterContext(List<String> afterContext, int remainingAfterLines) {
            this.afterContext = afterContext;
            this.remainingAfterLines = remainingAfterLines;
        }

        private List<String> afterContext() {
            return afterContext;
        }
    }
}
