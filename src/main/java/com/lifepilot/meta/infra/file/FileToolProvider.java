package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件工具提供者。
 *
 * <p>集中管理文件系统元能力工具：read / write / list / edit / manage。</p>
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
        tools.add(buildFileListTool(new FileListActionDispatchExecutor(
                new FileListToolExecutor(properties),
                new FileSearchToolExecutor(properties),
                new FileInfoToolExecutor(properties)
        )));
        tools.add(buildFileEditTool(new FilePatchToolExecutor(properties, editHistory, lintHook)));
        tools.add(buildFileManageTool(new FileManageActionDispatchExecutor(
                new FileMoveToolExecutor(properties),
                new FileCopyToolExecutor(properties),
                new FileDeleteToolExecutor(properties)
        )));

        return List.copyOf(tools);
    }

    /** 构建文件读取工具。 */
    private BuiltinTool buildFileReadTool(FileReadToolExecutor executor) {
        return BuiltinTool.builder()
                .id("file.read")
                .category(ToolCategory.PERCEPTION)
                .name("读取文件")
                .description("读取指定路径的单个文件内容。当你知道文件路径并需要查看其内容时使用。" +
                        "支持行范围读取、maxChars 截断和编码指定，返回 totalLines 字段。" +
                        "若需列出目录内容或跨文件搜索，请使用 file.list")
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
                .name("写入文件")
                .description("创建新文件或覆盖/追加内容到现有文件。mode=write（默认）原子覆写，mode=append 追加到末尾。" +
                        "支持自动创建父目录。若需精确修改文件中的某几行，请使用 file.edit")
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

    /** 构建统一文件查询工具。 */
    private BuiltinTool buildFileListTool(FileListActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("file.list")
                .category(ToolCategory.PERCEPTION)
                .name("文件查询")
                .description("查询文件系统信息。通过 action 参数支持三类操作：" +
                        "list=列出目录内容，search=递归搜索文件内容，info=查询文件或目录元数据。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action", "path"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("list", "search", "info"),
                                        "description", "文件查询动作类型")),
                                Map.entry("path", Map.of(
                                        "type", "string",
                                        "description", "目标路径；三种动作都需要")),
                                Map.entry("maxDepth", Map.of(
                                        "type", "integer",
                                        "description", "action=list 时的最大遍历深度，默认 3")),
                                Map.entry("pattern", Map.of(
                                        "type", "string",
                                        "description", "action=list 时表示 glob 过滤模式；action=search 时表示内容正则表达式")),
                                Map.entry("maxEntries", Map.of(
                                        "type", "integer",
                                        "description", "action=list 时最大返回条目数，默认 200")),
                                Map.entry("filePattern", Map.of(
                                        "type", "string",
                                        "description", "action=search 时的文件名 glob 过滤模式（如 *.java）")),
                                Map.entry("maxResults", Map.of(
                                        "type", "integer",
                                        "description", "action=search 时最大返回结果数，默认 50")),
                                Map.entry("offset", Map.of(
                                        "type", "integer",
                                        "description", "action=search 时分页偏移量，默认 0")),
                                Map.entry("limit", Map.of(
                                        "type", "integer",
                                        "description", "action=search 时分页每页数量，默认等于 maxResults")),
                                Map.entry("contextLines", Map.of(
                                        "type", "integer",
                                        "description", "action=search 时匹配行前后上下文行数，默认 0"))
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(INFRA_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    /** 构建统一文件编辑工具。 */
    private BuiltinTool buildFileEditTool(FilePatchToolExecutor executor) {
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
                .id("file.edit")
                .category(ToolCategory.ACTION)
                .name("编辑文件")
                .description("对现有文件执行精确的行级修改（insert/replace/delete），原子写入。" +
                        "当需要修改文件中的特定几行代码或文本时使用，比 file.write 更安全（不会意外覆盖整个文件）")
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

    /** 构建统一文件管理工具。 */
    private BuiltinTool buildFileManageTool(FileManageActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("file.manage")
                .category(ToolCategory.ACTION)
                .name("文件管理")
                .description("管理文件与目录。通过 action 参数支持三类操作：" +
                        "move=移动文件或目录，copy=复制文件，delete=删除文件或目录。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("move", "copy", "delete"),
                                        "description", "文件管理动作类型")),
                                Map.entry("source", Map.of(
                                        "type", "string",
                                        "description", "源路径；action=move/copy 时必填")),
                                Map.entry("destination", Map.of(
                                        "type", "string",
                                        "description", "目标路径；action=move/copy 时必填")),
                                Map.entry("path", Map.of(
                                        "type", "string",
                                        "description", "目标文件或目录路径；action=delete 时必填")),
                                Map.entry("overwrite", Map.of(
                                        "type", "boolean",
                                        "description", "action=move/copy 时目标已存在是否覆盖，默认 false")),
                                Map.entry("recursive", Map.of(
                                        "type", "boolean",
                                        "description", "action=delete 时是否递归删除目录内容，默认 false"))
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination", "path")
                ))
                .tags(INFRA_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}
