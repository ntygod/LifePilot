package com.lifepilot.meta.infra.git;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git Blame 工具执行器 — 执行 {@code git blame --porcelain} 并解析为结构化行信息。
 *
 * <p>支持行范围限定，输出每行的 commit hash、作者、日期和内容。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitBlameToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitBlameToolExecutor.class);

    private final GitCommandExecutor gitCmd;
    private final int maxBlameLines;

    public GitBlameToolExecutor(GitCommandExecutor gitCmd, MetaProperties.Infra.Git gitConfig) {
        this.gitCmd = gitCmd;
        this.maxBlameLines = gitConfig.getMaxBlameLines();
    }

    /**
     * 执行 Git blame。
     *
     * @param input 工具输入，必需参数 filePath，可选参数 path、startLine、endLine
     * @return 结构化 blame 信息
     */
    public ToolResult execute(ToolInput input) {
        try {
            String filePath;
            try {
                filePath = input.getParam("filePath", String.class);
            } catch (IllegalArgumentException e) {
                return ToolResult.error("缺少必需参数: filePath");
            }

            String pathStr = input.getOptionalParam("path", String.class)
                    .orElse(null);
            if (pathStr == null) {
                return ToolResult.error("必须指定 path 参数（Git 仓库路径）");
            }
            Path repoPath = Path.of(pathStr).toAbsolutePath().normalize();

            if (!gitCmd.isGitRepo(repoPath)) {
                return ToolResult.error("指定路径不是 Git 仓库: " + repoPath);
            }

            int startLine = input.getOptionalParam("startLine", Number.class)
                    .map(Number::intValue)
                    .orElse(1);
            int endLine = input.getOptionalParam("endLine", Number.class)
                    .map(Number::intValue)
                    .orElse(startLine + maxBlameLines - 1);

            // 限制行范围，防止输出过大
            int lineCount = endLine - startLine + 1;
            if (lineCount > maxBlameLines) {
                endLine = startLine + maxBlameLines - 1;
            }

            var args = new ArrayList<String>();
            args.add("blame");
            args.add("-L");
            args.add(startLine + "," + endLine);
            args.add("--porcelain");
            args.add(filePath);

            var result = gitCmd.execute(repoPath, args.toArray(String[]::new));
            if (!result.ok()) {
                return ToolResult.error("git blame 执行失败: " + result.stderr());
            }

            List<Map<String, Object>> lines = parsePorcelainBlame(result.stdout(), startLine);

            var data = new LinkedHashMap<String, Object>();
            data.put("lines", lines);
            return ToolResult.success(data);

        } catch (Exception e) {
            log.error("git blame 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git blame 执行失败: " + e.getMessage());
        }
    }

    /**
     * 解析 porcelain 格式的 blame 输出。
     *
     * <p>porcelain 格式以 commit hash 开头行标记新条目，
     * 后跟元数据行（author、author-time 等），最后以 tab 开头的行为实际内容。</p>
     */
    private List<Map<String, Object>> parsePorcelainBlame(String output, int startLine) {
        var lines = new ArrayList<Map<String, Object>>();
        if (output == null || output.isBlank()) {
            return lines;
        }

        String currentHash = "";
        String currentAuthor = "";
        String currentDate = "";
        int currentLineNumber = startLine;
        boolean expectContent = false;

        for (String rawLine : output.split("\n")) {
            if (rawLine.isEmpty()) {
                continue;
            }

            if (rawLine.startsWith("\t")) {
                // 内容行（以 tab 开头）
                String content = rawLine.substring(1);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("lineNumber", currentLineNumber);
                entry.put("hash", currentHash);
                entry.put("author", currentAuthor);
                entry.put("date", currentDate);
                entry.put("content", content);
                lines.add(entry);
                currentLineNumber++;
                expectContent = false;
            } else if (!expectContent && rawLine.matches("^[0-9a-f]{40}\\s+.*")) {
                // commit hash 行：hash origLine resultLine [count]
                String[] parts = rawLine.split("\\s+", 4);
                currentHash = parts[0];
                if (parts.length >= 3) {
                    try {
                        currentLineNumber = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException ignored) {
                        // 保持当前行号
                    }
                }
                expectContent = true;
            } else if (rawLine.startsWith("author ")) {
                currentAuthor = rawLine.substring("author ".length());
            } else if (rawLine.startsWith("author-time ")) {
                // Unix 时间戳转 ISO 日期
                try {
                    long timestamp = Long.parseLong(rawLine.substring("author-time ".length()).trim());
                    currentDate = java.time.Instant.ofEpochSecond(timestamp)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toOffsetDateTime()
                            .toString();
                } catch (NumberFormatException e) {
                    currentDate = rawLine.substring("author-time ".length()).trim();
                }
            }
        }

        return lines;
    }
}
