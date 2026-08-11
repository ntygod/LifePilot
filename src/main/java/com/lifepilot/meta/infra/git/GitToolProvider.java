package com.lifepilot.meta.infra.git;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git 工具提供者。
 *
 * <p>集中管理 Git 元能力工具：query / mutate。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitToolProvider {

    private final GitCommandExecutor gitCmd;
    private final MetaProperties.Infra.Git gitConfig;

    public GitToolProvider(GitCommandExecutor gitCmd, MetaProperties.Infra.Git gitConfig) {
        this.gitCmd = gitCmd;
        this.gitConfig = gitConfig;
    }

    /**
     * 构建所有 Git 工具的 BuiltinTool 列表。
     *
     * @return Git 工具列表
     */
    public List<BuiltinTool> buildGitTools() {
        var queryExecutor = new GitQueryActionDispatchExecutor(
                new GitStatusToolExecutor(gitCmd),
                new GitDiffToolExecutor(gitCmd, gitConfig),
                new GitLogToolExecutor(gitCmd, gitConfig),
                new GitBlameToolExecutor(gitCmd, gitConfig)
        );
        var mutateExecutor = new GitMutateActionDispatchExecutor(
                new GitCommitToolExecutor(gitCmd),
                new GitStashToolExecutor(gitCmd),
                new GitBranchToolExecutor(gitCmd)
        );
        return List.of(
                buildGitQueryTool(queryExecutor),
                buildGitMutateTool(mutateExecutor)
        );
    }

    /** 构建统一 Git 查询工具。 */
    private BuiltinTool buildGitQueryTool(GitQueryActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("git.query")
                .category(ToolCategory.PERCEPTION)
                .name("Git 查询")
                .description("""
                        Git 只读查询。path 为仓库路径（必填）。
                        action: status(工作区/暂存区状态) diff(差异，staged=true看暂存区) log(提交日志，count默认10，filePath限定文件) blame(行级追溯，startLine/endLine限定范围，1-based)。
                        commit hash 或 branch 名作 diff/log 的范围参数。""")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action", "path"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("status", "diff", "log", "blame"),
                                        "description", "Git 查询动作类型")),
                                Map.entry("path", Map.of(
                                        "type", "string",
                                        "description", "Git 仓库路径（必填）")),
                                Map.entry("staged", Map.of(
                                        "type", "boolean",
                                        "description", "action=diff 时是否查看暂存区差异（--staged），默认 false")),
                                Map.entry("filePath", Map.of(
                                        "type", "string",
                                        "description", "action=diff/log/blame 时限定查看的文件路径")),
                                Map.entry("count", Map.of(
                                        "type", "integer",
                                        "description", "action=log 时返回的提交数量，默认 10，最大 " + gitConfig.getMaxLogEntries())),
                                Map.entry("startLine", Map.of(
                                        "type", "integer",
                                        "description", "action=blame 时起始行号（1-based），默认 1")),
                                Map.entry("endLine", Map.of(
                                        "type", "integer",
                                        "description", "action=blame 时结束行号（1-based），默认 startLine + " + gitConfig.getMaxBlameLines()))
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(List.of("git", "仓库", "提交", "历史", "差异", "查询", "status", "diff", "log", "blame"))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    /** 构建统一 Git 变更工具。 */
    private BuiltinTool buildGitMutateTool(GitMutateActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("git.mutate")
                .category(ToolCategory.ACTION)
                .name("Git 变更")
                .description("""
                        Git 写操作（高风险）。path 为仓库路径（必填）。
                        action: commit(提交，message必填，files指定文件否则全提交) stash(暂存，可带message) branch(分支管理，支持create/delete/list/switch，branchName/branchAction参数)。
                        push 暂不支持，告知用户手动推送。""")
                .inputSchema(JsonSchema.of(buildMutateSchema()))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(List.of("git", "提交", "分支", "暂存", "推送", "commit", "stash", "branch", "push"))
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    private Map<String, Object> buildMutateSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("commit", "stash", "branch"),
                "description", "Git 写操作类型"
        ));
        properties.put("path", Map.of(
                "type", "string",
                "description", "Git 仓库路径（必填）"
        ));
        properties.put("message", Map.of(
                "type", "string",
                "description", "action=commit 时的提交信息；action=stash 时可作为 stash 描述"
        ));
        properties.put("files", Map.of(
                "type", "array",
                "description", "action=commit 时要先暂存的文件路径列表（可选）",
                "items", Map.of("type", "string")
        ));
        properties.put("stashAction", Map.of(
                "type", "string",
                "description", "action=stash 时的具体操作：push / pop / list / drop"
        ));
        properties.put("index", Map.of(
                "type", "integer",
                "description", "action=stash 且 stashAction=drop 时的 stash 索引号，默认 0"
        ));
        properties.put("branchAction", Map.of(
                "type", "string",
                "description", "action=branch 时的具体操作：list / create / switch / delete"
        ));
        properties.put("name", Map.of(
                "type", "string",
                "description", "action=branch 时的分支名称；create/switch/delete 时必填"
        ));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action", "path"));
        schema.put("properties", properties);
        return schema;
    }
}
