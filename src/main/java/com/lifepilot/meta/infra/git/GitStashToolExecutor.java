package com.lifepilot.meta.infra.git;

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
 * Git Stash 工具执行器 — 支持 push/pop/list/drop 四种操作。
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitStashToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitStashToolExecutor.class);

    private final GitCommandExecutor gitCmd;

    public GitStashToolExecutor(GitCommandExecutor gitCmd) {
        this.gitCmd = gitCmd;
    }

    /**
     * 执行 Git stash 操作。
     *
     * @param input 工具输入，必需参数 stashAction（push/pop/list/drop），兼容旧参数 action；可选参数 path、message、index
     * @return 操作结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String action = input.getOptionalParam("stashAction", String.class)
                    .or(() -> input.getOptionalParam("action", String.class))
                    .orElse(null);
            if (action == null || action.isBlank()) {
                return ToolResult.error("缺少必需参数: stashAction");
            }

            String pathStr = input.getOptionalParam("path", String.class)
                    .orElse(System.getProperty("user.dir"));
            Path repoPath = Path.of(pathStr).toAbsolutePath().normalize();

            if (!gitCmd.isGitRepo(repoPath)) {
                return ToolResult.error("指定路径不是 Git 仓库: " + repoPath);
            }

            return switch (action.toLowerCase()) {
                case "push" -> executePush(repoPath, input);
                case "pop" -> executePop(repoPath);
                case "list" -> executeList(repoPath);
                case "drop" -> executeDrop(repoPath, input);
                default -> ToolResult.error("不支持的 stash 操作: " + action + "，支持 push/pop/list/drop");
            };

        } catch (Exception e) {
            log.error("git stash 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git stash 执行失败: " + e.getMessage());
        }
    }

    private ToolResult executePush(Path repoPath, ToolInput input) {
        String message = input.getOptionalParam("message", String.class).orElse(null);
        var args = new ArrayList<String>();
        args.add("stash");
        args.add("push");
        if (message != null && !message.isBlank()) {
            args.add("-m");
            args.add(message);
        }

        var result = gitCmd.execute(repoPath, args.toArray(String[]::new));
        if (!result.ok()) {
            return ToolResult.error("git stash push 执行失败: " + result.stderr());
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "push");
        data.put("output", result.stdout().trim());
        return ToolResult.success(data);
    }

    private ToolResult executePop(Path repoPath) {
        var result = gitCmd.execute(repoPath, "stash", "pop");
        if (!result.ok()) {
            return ToolResult.error("git stash pop 执行失败: " + result.stderr());
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "pop");
        data.put("output", result.stdout().trim());
        return ToolResult.success(data);
    }

    private ToolResult executeList(Path repoPath) {
        var result = gitCmd.execute(repoPath, "stash", "list");
        if (!result.ok()) {
            return ToolResult.error("git stash list 执行失败: " + result.stderr());
        }

        List<Map<String, String>> entries = new ArrayList<>();
        String output = result.stdout();
        if (output != null && !output.isBlank()) {
            for (String line : output.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                // 格式：stash@{0}: WIP on branch: message 或 stash@{0}: On branch: message
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String index = line.substring(0, colonIndex).trim();
                    String description = line.substring(colonIndex + 1).trim();
                    entries.add(Map.of("index", index, "description", description));
                } else {
                    entries.add(Map.of("index", "", "description", line.trim()));
                }
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "list");
        data.put("entries", entries);
        return ToolResult.success(data);
    }

    private ToolResult executeDrop(Path repoPath, ToolInput input) {
        int index = input.getOptionalParam("index", Number.class)
                .map(Number::intValue)
                .orElse(0);

        var result = gitCmd.execute(repoPath, "stash", "drop", "stash@{" + index + "}");
        if (!result.ok()) {
            return ToolResult.error("git stash drop 执行失败: " + result.stderr());
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "drop");
        data.put("index", index);
        data.put("output", result.stdout().trim());
        return ToolResult.success(data);
    }
}
