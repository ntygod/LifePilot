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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * 文件搜索工具 — 递归搜索文件内容，支持正则和 glob 过滤。
 *
 * <p>安全机制：通过 {@link PathSecurityChecker} 校验路径白名单/黑名单。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class FileSearchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileSearchToolExecutor.class);

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
     * @param input 工具输入，必需参数 path 和 pattern，可选 filePattern 和 maxResults
     * @return 包含 matches 和 totalMatches 的结构化结果
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

        String filePattern = input.getOptionalParam("filePattern", String.class)
                .orElse(null);

        int maxResults = input.getOptionalParam("maxResults", Number.class)
                .map(Number::intValue)
                .orElse(50);

        Path dirPath = Path.of(pathStr);

        // 路径安全检查
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

        // 编译正则
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

            try (Stream<Path> walk = Files.walk(dirPath)) {
                var files = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> fileMatcher == null || fileMatcher.matches(p.getFileName()))
                        .toList();

                for (Path file : files) {
                    if (totalMatches >= maxResults) {
                        break;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            if (regex.matcher(lines.get(i)).find()) {
                                totalMatches++;
                                if (matches.size() < maxResults) {
                                    var match = new LinkedHashMap<String, Object>();
                                    match.put("file", dirPath.relativize(file).toString());
                                    match.put("line", i + 1);
                                    match.put("content", lines.get(i));
                                    matches.add(match);
                                }
                            }
                        }
                    } catch (IOException e) {
                        // 跳过无法读取的文件（如二进制文件）
                        log.debug("跳过无法读取的文件: path={}, error={}", file, e.getMessage());
                    }
                }
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("matches", matches);
            data.put("totalMatches", totalMatches);

            log.debug("文件搜索完成: path={}, pattern={}, totalMatches={}", pathStr, patternStr, totalMatches);
            return ToolResult.success(Map.copyOf(data));

        } catch (IOException e) {
            log.error("文件搜索失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件搜索失败: " + e.getMessage());
        }
    }
}
