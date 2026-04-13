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
 * Git Branch 工具执行器 — 支持 list/create/switch/delete 四种操作。
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitBranchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitBranchToolExecutor.class);

    /** 分支列表格式分隔符。 */
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String BRANCH_FORMAT = "%(refname:short)" + FIELD_SEPARATOR
            + "%(objectname:short)" + FIELD_SEPARATOR
            + "%(upstream:short)" + FIELD_SEPARATOR
            + "%(HEAD)";

    private final GitCommandExecutor gitCmd;

    public GitBranchToolExecutor(GitCommandExecutor gitCmd) {
        this.gitCmd = gitCmd;
    }

    /**
     * 执行 Git branch 操作。
     *
     * @param input 工具输入，必需参数 branchAction（list/create/switch/delete），兼容旧参数 action；可选参数 path、name
     * @return 操作结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String action = input.getOptionalParam("branchAction", String.class)
                    .or(() -> input.getOptionalParam("action", String.class))
                    .orElse(null);
            if (action == null || action.isBlank()) {
                return ToolResult.error("缺少必需参数: branchAction");
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

            return switch (action.toLowerCase()) {
                case "list" -> executeList(repoPath);
                case "create" -> executeCreate(repoPath, input);
                case "switch" -> executeSwitch(repoPath, input);
                case "delete" -> executeDelete(repoPath, input);
                default -> ToolResult.error("不支持的 branch 操作: " + action + "，支持 list/create/switch/delete");
            };

        } catch (Exception e) {
            log.error("git branch 执行异常: {}", e.getMessage(), e);
            return ToolResult.error("git branch 执行失败: " + e.getMessage());
        }
    }

    private ToolResult executeList(Path repoPath) {
        var result = gitCmd.execute(repoPath, "branch", "-a", "--format=" + BRANCH_FORMAT);
        if (!result.ok()) {
            return ToolResult.error("git branch list 执行失败: " + result.stderr());
        }

        List<Map<String, Object>> branches = new ArrayList<>();
        String output = result.stdout();
        if (output != null && !output.isBlank()) {
            for (String line : output.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(FIELD_SEPARATOR, 4);
                if (parts.length < 4) {
                    log.debug("跳过格式异常的分支行: {}", line);
                    continue;
                }

                var branch = new LinkedHashMap<String, Object>();
                branch.put("name", parts[0].trim());
                branch.put("hash", parts[1].trim());
                branch.put("upstream", parts[2].trim());
                branch.put("current", "*".equals(parts[3].trim()));
                branches.add(branch);
            }
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "list");
        data.put("branches", branches);
        return ToolResult.success(data);
    }

    private ToolResult executeCreate(Path repoPath, ToolInput input) {
        String name = extractBranchName(input);
        if (name == null) {
            return ToolResult.error("缺少必需参数: name（分支名称）");
        }

        var result = gitCmd.execute(repoPath, "branch", name);
        if (!result.ok()) {
            return ToolResult.error("git branch create 执行失败: " + result.stderr());
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "create");
        data.put("name", name);
        return ToolResult.success(data);
    }

    private ToolResult executeSwitch(Path repoPath, ToolInput input) {
        String name = extractBranchName(input);
        if (name == null) {
            return ToolResult.error("缺少必需参数: name（分支名称）");
        }

        var result = gitCmd.execute(repoPath, "switch", name);
        if (!result.ok()) {
            return ToolResult.error("git switch 执行失败: " + result.stderr());
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "switch");
        data.put("name", name);
        data.put("output", result.stdout().trim());
        return ToolResult.success(data);
    }

    private ToolResult executeDelete(Path repoPath, ToolInput input) {
        String name = extractBranchName(input);
        if (name == null) {
            return ToolResult.error("缺少必需参数: name（分支名称）");
        }

        // 使用 -d（安全删除）而非 -D（强制删除）
        var result = gitCmd.execute(repoPath, "branch", "-d", name);
        if (!result.ok()) {
            return ToolResult.error("git branch delete 执行失败: " + result.stderr());
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("action", "delete");
        data.put("name", name);
        data.put("output", result.stdout().trim());
        return ToolResult.success(data);
    }

    private String extractBranchName(ToolInput input) {
        return input.getOptionalParam("name", String.class)
                .filter(n -> !n.isBlank())
                .orElse(null);
    }
}
