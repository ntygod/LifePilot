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
 * Git Log 工具执行器 — 执行 {@code git log} 并解析为结构化提交列表。
 *
 * <p>支持限定条目数和文件路径过滤。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitLogToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitLogToolExecutor.class);

    /** 日志格式分隔符，使用不常见的 ASCII 控制字符避免与提交信息冲突。 */
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String FORMAT = "%H" + FIELD_SEPARATOR + "%an" + FIELD_SEPARATOR + "%aI" + FIELD_SEPARATOR + "%s";

    private final GitCommandExecutor gitCmd;
    private final int maxLogEntries;

    public GitLogToolExecutor(GitCommandExecutor gitCmd, MetaProperties.Infra.Git gitConfig) {
        this.gitCmd = gitCmd;
        this.maxLogEntries = gitConfig.getMaxLogEntries();
    }

    /**
     * 获取 Git 提交日志。
     *
     * @param input 工具输入，可选参数 path、count、filePath
     * @return 结构化提交列表
     */
    public ToolResult execute(ToolInput input) {
        try {
            String pathStr = input.getOptionalParam("path", String.class)
                    .orElse(null);
            if (pathStr == null) {
                return ToolResult.error("必须指定 path 参数（Git 仓库路径）");
            }
            Path repoPath = Path.of(pathStr).toAbsolutePath().normalize();

            if (!gitCmd.isGitRepo(repoPath)) {
                return ToolResult.error("指定路径不是 Git 仓库: " + repoPath);
            }

            int count = input.getOptionalParam("count", Number.class)
                    .map(Number::intValue)
                    .map(n -> Math.max(1, Math.min(n, maxLogEntries)))
                    .orElse(10);
            String filePath = input.getOptionalParam("filePath", String.class).orElse(null);

            var args = new ArrayList<String>();
            args.add("log");
            args.add("--format=" + FORMAT);
            args.add("-n");
            args.add(String.valueOf(count));
            if (filePath != null && !filePath.isBlank()) {
                args.add("--");
                args.add(filePath);
            }

            var result = gitCmd.execute(repoPath, args.toArray(String[]::new));
            if (!result.ok()) {
                return ToolResult.error("git log 执行失败: " + result.stderr());
            }

            List<Map<String, String>> commits = parseLogOutput(result.stdout());

            var data = new LinkedHashMap<String, Object>();
            data.put("commits", commits);
            return ToolResult.success(data);

        } catch (Exception e) {
            log.error("git log 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git log 执行失败: " + e.getMessage());
        }
    }

    /**
     * 解析 git log 输出。
     */
    private List<Map<String, String>> parseLogOutput(String output) {
        var commits = new ArrayList<Map<String, String>>();
        if (output == null || output.isBlank()) {
            return commits;
        }

        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(FIELD_SEPARATOR, 4);
            if (parts.length < 4) {
                log.debug("跳过格式异常的日志行: {}", line);
                continue;
            }

            var commit = new LinkedHashMap<String, String>();
            commit.put("hash", parts[0].trim());
            commit.put("author", parts[1].trim());
            commit.put("date", parts[2].trim());
            commit.put("message", parts[3].trim());
            commits.add(commit);
        }

        return commits;
    }
}
