package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.FileEditToolProvider;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.model.ToolTier;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件工具提供者 — 构建所有文件系统工具的 {@link BuiltinTool} 列表。
 *
 * <p>从 {@link com.lifepilot.meta.infra.InfraToolProvider} 中拆分出来，
 * 集中管理文件工具的注册逻辑，便于后续新增文件工具。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class FileToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final MetaProperties properties;
    @Nullable
    private final FileEditHistory editHistory;
    @Nullable
    private final LintHookExecutor lintHook;

    public FileToolProvider(MetaProperties properties) {
        this.properties = properties;
        this.editHistory = null;
        this.lintHook = null;
    }

    /**
     * 构造函数 — 支持注入文件编辑历史和 lint 钩子。
     *
     * @param properties   元能力配置
     * @param editHistory  文件编辑历史（可为 null）
     * @param lintHook     lint 钩子执行器（可为 null）
     */
    public FileToolProvider(MetaProperties properties,
                            @Nullable FileEditHistory editHistory,
                            @Nullable LintHookExecutor lintHook) {
        this.properties = properties;
        this.editHistory = editHistory;
        this.lintHook = lintHook;
    }

    /**
     * 构建所有文件工具的 BuiltinTool 列表。
     *
     * @return 文件工具列表
     */
    public List<BuiltinTool> buildFileTools() {
        var tools = new ArrayList<BuiltinTool>();

        tools.add(buildFileReadTool(new FileReadToolExecutor(properties)));
        tools.add(buildFileWriteTool(new FileWriteToolExecutor(properties, editHistory, lintHook)));
        tools.add(buildFileListTool(new FileListToolExecutor(properties)));
        tools.add(buildFileSearchTool(new FileSearchToolExecutor(properties)));
        tools.add(buildFileDeleteTool(new FileDeleteToolExecutor(properties)));
        tools.add(buildFileCopyTool(new FileCopyToolExecutor(properties)));
        tools.add(buildFileMoveTool(new FileMoveToolExecutor(properties)));
        tools.add(buildFileInfoTool(new FileInfoToolExecutor(properties)));
        tools.add(buildFilePatchTool(new FilePatchToolExecutor(properties, editHistory, lintHook)));

        // 文件编辑历史工具（undo/redo/diff）
        if (editHistory != null) {
            var editToolProvider = new FileEditToolProvider(editHistory);
            tools.addAll(editToolProvider.buildEditHistoryTools());
        }

        return List.copyOf(tools);
    }

    /** 构建文件读取工具。 */
    private BuiltinTool buildFileReadTool(FileReadToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.read")
                .category(ToolCategory.PERCEPTION)
                .tier(ToolTier.CORE)
                .name("读取文件")
                .description("读取指定路径的文件内容，支持行范围读取、maxChars 截断和编码指定。返回 totalLines 字段")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "文件路径"),
                                "encoding", Map.of("type", "string",
                                        "description", "文件编码（如 UTF-8、GBK），默认 UTF-8"),
                                "startLine", Map.of("type", "integer",
                                        "description", "起始行号（1-based），超出范围自动调整，可选"),
                                "endLine", Map.of("type", "integer",
                                        "description", "结束行号（1-based），超出范围自动调整，可选"),
                                "maxChars", Map.of("type", "integer",
                                        "description", "最大返回字符数，按完整行截断，默认 30000")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件写入工具（支持 write/append 两种模式）。 */
    private BuiltinTool buildFileWriteTool(FileWriteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.write")
                .category(ToolCategory.ACTION)
                .tier(ToolTier.CORE)
                .name("写入文件")
                .description("写入文件内容。mode=write（默认）原子覆写，mode=append 追加到末尾。支持自动创建父目录")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path", "content"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目标文件路径"),
                                "content", Map.of("type", "string",
                                        "description", "要写入的文件内容"),
                                "mode", Map.of("type", "string",
                                        "description", "写入模式：write（覆写，默认）或 append（追加到末尾）"),
                                "createDirectories", Map.of("type", "boolean",
                                        "description", "父目录不存在时是否自动创建，默认 true")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件列表工具。 */
    private BuiltinTool buildFileListTool(FileListToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.list")
                .category(ToolCategory.PERCEPTION)
                .tier(ToolTier.CORE)
                .name("列出目录")
                .description("列出指定目录的文件和子目录，支持深度限制、glob 过滤、maxEntries 截断和目录优先排序。替代已移除的 file.find 别名")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目录路径"),
                                "maxDepth", Map.of("type", "integer",
                                        "description", "最大遍历深度，默认 3"),
                                "pattern", Map.of("type", "string",
                                        "description", "glob 过滤模式（如 *.java），可选"),
                                "maxEntries", Map.of("type", "integer",
                                        "description", "最大返回条目数，默认 200")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件搜索工具。 */
    private BuiltinTool buildFileSearchTool(FileSearchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.search")
                .category(ToolCategory.PERCEPTION)
                .tier(ToolTier.CORE)
                .name("搜索文件内容")
                .description("递归搜索目录下文件内容，支持正则表达式、glob 过滤、上下文行和二进制文件自动跳过。替代已移除的 file.grep 别名")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path", "pattern"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "搜索起始目录路径"),
                                "pattern", Map.of("type", "string",
                                        "description", "搜索内容的正则表达式"),
                                "filePattern", Map.of("type", "string",
                                        "description", "文件名 glob 过滤模式（如 *.java），可选"),
                                "maxResults", Map.of("type", "integer",
                                        "description", "最大返回结果数，默认 50"),
                                "offset", Map.of("type", "integer",
                                        "description", "分页偏移量，跳过前 offset 条匹配，默认 0"),
                                "limit", Map.of("type", "integer",
                                        "description", "分页每页数量，默认等于 maxResults"),
                                "contextLines", Map.of("type", "integer",
                                        "description", "匹配行前后上下文行数，默认 0")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件补丁工具。 */
    private BuiltinTool buildFilePatchTool(FilePatchToolExecutor executor) {
        // operations 数组项的 schema
        var itemProperties = new LinkedHashMap<String, Object>();
        itemProperties.put("type", Map.of("type", "string",
                "description", "操作类型: insert / replace / delete"));
        itemProperties.put("line", Map.of("type", "integer",
                "description", "目标行号（1-based）"));
        itemProperties.put("endLine", Map.of("type", "integer",
                "description", "结束行号（replace/delete 时可选，默认等于 line）"));
        itemProperties.put("content", Map.of("type", "string",
                "description", "插入或替换的内容（insert/replace 时必需）"));

        var itemSchema = new LinkedHashMap<String, Object>();
        itemSchema.put("type", "object");
        itemSchema.put("required", List.of("type", "line"));
        itemSchema.put("properties", itemProperties);

        return BuiltinTool.builder()
                .id("file.patch")
                .category(ToolCategory.ACTION)
                .tier(ToolTier.CORE)
                .name("补丁文件")
                .description("对文件执行行级 insert/replace/delete 操作，原子写入。MEDIUM 风险")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path", "operations"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目标文件路径"),
                                "operations", Map.of("type", "array",
                                        "description", "行级操作列表",
                                        "items", itemSchema)
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件信息工具。 */
    private BuiltinTool buildFileInfoTool(FileInfoToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.info")
                .category(ToolCategory.PERCEPTION)
                .name("文件信息")
                .description("查询文件或目录的元数据，包括大小、修改时间、权限和 MIME 类型")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "文件或目录路径")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件移动工具。 */
    private BuiltinTool buildFileMoveTool(FileMoveToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.move")
                .category(ToolCategory.ACTION)
                .name("移动文件")
                .description("原子移动文件到目标路径，支持覆盖控制。HIGH 风险，每次执行需用户确认")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("source", "destination"),
                        "properties", Map.of(
                                "source", Map.of("type", "string",
                                        "description", "源文件路径"),
                                "destination", Map.of("type", "string",
                                        "description", "目标路径"),
                                "overwrite", Map.of("type", "boolean",
                                        "description", "目标已存在时是否覆盖，默认 false")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件复制工具。 */
    private BuiltinTool buildFileCopyTool(FileCopyToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.copy")
                .category(ToolCategory.ACTION)
                .name("复制文件")
                .description("复制文件到目标路径，支持覆盖控制。MEDIUM 风险")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("source", "destination"),
                        "properties", Map.of(
                                "source", Map.of("type", "string",
                                        "description", "源文件路径"),
                                "destination", Map.of("type", "string",
                                        "description", "目标路径"),
                                "overwrite", Map.of("type", "boolean",
                                        "description", "目标已存在时是否覆盖，默认 false")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件删除工具。 */
    private BuiltinTool buildFileDeleteTool(FileDeleteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.delete")
                .category(ToolCategory.ACTION)
                .name("删除文件")
                .description("删除文件或目录，支持递归删除非空目录。HIGH 风险，每次执行需用户确认")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目标文件或目录路径"),
                                "recursive", Map.of("type", "boolean",
                                        "description", "是否递归删除目录内容，默认 false")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.DELETE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

}
