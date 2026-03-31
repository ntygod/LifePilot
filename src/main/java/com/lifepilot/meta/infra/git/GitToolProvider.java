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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git 工具提供者 — 构建所有 Git 工具的 {@link BuiltinTool} 列表。
 *
 * <p>提供 7 个 Git 工具：status、diff、log、commit、blame、stash、branch。
 * 读操作（status/diff/log/blame）为 PARALLEL_SAFE，
 * 写操作（commit/stash/branch）为 SEQUENTIAL。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class GitToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final GitCommandExecutor gitCmd;
    private final MetaProperties.Infra.Git gitConfig;

    public GitToolProvider(GitCommandExecutor gitCmd, MetaProperties.Infra.Git gitConfig) {
        this.gitCmd = gitCmd;
        this.gitConfig = gitConfig;
    }

    /**
     * 构建所有 Git 工具的 BuiltinTool 列表。
     *
     * @return 7 个 Git 工具列表
     */
    public List<BuiltinTool> buildGitTools() {
        var tools = new ArrayList<BuiltinTool>();

        tools.add(buildGitStatusTool(new GitStatusToolExecutor(gitCmd)));
        tools.add(buildGitDiffTool(new GitDiffToolExecutor(gitCmd, gitConfig)));
        tools.add(buildGitLogTool(new GitLogToolExecutor(gitCmd, gitConfig)));
        tools.add(buildGitCommitTool(new GitCommitToolExecutor(gitCmd)));
        tools.add(buildGitBlameTool(new GitBlameToolExecutor(gitCmd, gitConfig)));
        tools.add(buildGitStashTool(new GitStashToolExecutor(gitCmd)));
        tools.add(buildGitBranchTool(new GitBranchToolExecutor(gitCmd)));

        return List.copyOf(tools);
    }

    /** 构建 Git Status 工具。 */
    private BuiltinTool buildGitStatusTool(GitStatusToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.status")
                .category(ToolCategory.PERCEPTION)
                .name("Git 仓库状态")
                .description("查看当前 Git 仓库的文件状态，包括暂存区、工作区和未跟踪文件的变更情况。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Git 仓库路径，默认为当前工作目录")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Git Diff 工具。 */
    private BuiltinTool buildGitDiffTool(GitDiffToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.diff")
                .category(ToolCategory.PERCEPTION)
                .name("Git 差异对比")
                .description("查看 Git 仓库的文件差异，支持工作区和暂存区差异对比。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Git 仓库路径，默认为当前工作目录"),
                                "staged", Map.of("type", "boolean",
                                        "description", "是否查看暂存区差异（--staged），默认 false"),
                                "filePath", Map.of("type", "string",
                                        "description", "限定查看差异的文件路径，可选")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Git Log 工具。 */
    private BuiltinTool buildGitLogTool(GitLogToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.log")
                .category(ToolCategory.PERCEPTION)
                .name("Git 提交日志")
                .description("查看 Git 仓库的提交历史，返回 hash、作者、日期和提交信息。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Git 仓库路径，默认为当前工作目录"),
                                "count", Map.of("type", "integer",
                                        "description", "返回的提交数量，默认 10，最大 " + gitConfig.getMaxLogEntries()),
                                "filePath", Map.of("type", "string",
                                        "description", "限定查看日志的文件路径，可选")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Git Commit 工具。 */
    private BuiltinTool buildGitCommitTool(GitCommitToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.commit")
                .category(ToolCategory.ACTION)
                .name("Git 提交")
                .description("提交暂存区的变更到 Git 仓库，可选先暂存指定文件。")
                .inputSchema(JsonSchema.of(buildCommitSchema()))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Git Blame 工具。 */
    private BuiltinTool buildGitBlameTool(GitBlameToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.blame")
                .category(ToolCategory.PERCEPTION)
                .name("Git 逐行追溯")
                .description("逐行追溯文件的修改历史，查看每行的提交 hash、作者、日期和内容。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("filePath"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Git 仓库路径，默认为当前工作目录"),
                                "filePath", Map.of("type", "string",
                                        "description", "要追溯的文件路径（相对于仓库根目录）"),
                                "startLine", Map.of("type", "integer",
                                        "description", "起始行号（1-based），默认 1"),
                                "endLine", Map.of("type", "integer",
                                        "description", "结束行号（1-based），默认 startLine + " + gitConfig.getMaxBlameLines())
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Git Stash 工具。 */
    private BuiltinTool buildGitStashTool(GitStashToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.stash")
                .category(ToolCategory.ACTION)
                .name("Git 暂存管理")
                .description("管理 Git stash（临时暂存区），支持 push（保存）、pop（恢复）、list（列表）、drop（删除）操作。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Git 仓库路径，默认为当前工作目录"),
                                "action", Map.of("type", "string",
                                        "description", "操作类型：push / pop / list / drop"),
                                "message", Map.of("type", "string",
                                        "description", "stash 保存时的描述信息（push 操作时可选）"),
                                "index", Map.of("type", "integer",
                                        "description", "stash 索引号（drop 操作时可选，默认 0）")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建 Git Branch 工具。 */
    private BuiltinTool buildGitBranchTool(GitBranchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("git.branch")
                .category(ToolCategory.ACTION)
                .name("Git 分支管理")
                .description("管理 Git 分支，支持 list（列出）、create（创建）、switch（切换）、delete（删除）操作。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Git 仓库路径，默认为当前工作目录"),
                                "action", Map.of("type", "string",
                                        "description", "操作类型：list / create / switch / delete"),
                                "name", Map.of("type", "string",
                                        "description", "分支名称（create/switch/delete 操作时必需）")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /**
     * 构建 commit 工具的 inputSchema。
     *
     * <p>单独方法处理是因为 files 参数为 array 类型，需要更复杂的 schema 结构。</p>
     */
    private Map<String, Object> buildCommitSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("path", Map.of("type", "string",
                "description", "Git 仓库路径，默认为当前工作目录"));
        properties.put("message", Map.of("type", "string",
                "description", "提交信息"));
        properties.put("files", Map.of("type", "array",
                "description", "要先暂存的文件路径列表（可选，不指定则提交当前暂存区内容）",
                "items", Map.of("type", "string")));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("message"));
        schema.put("properties", properties);
        return schema;
    }
}
