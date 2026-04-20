package com.lifepilot.meta.infra.file;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.ExcelParser;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PdfParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.PowerpointParser;
import com.lifepilot.knowledge.parser.WordParser;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.history.FileEditHistory;
import com.lifepilot.meta.infra.file.history.LintHookExecutor;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
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
 * <p>集中管理文件系统元能力工具:read / write / list / edit / manage。
 * 所有 Executor 共享同一个 {@link PathSecurityChecker} 实例。</p>
 *
 * <p>Phase 0 后:{@code file.read} 合并了文档解析能力,通过内部 {@link DocumentParserService}
 * 按扩展名自动路由(docx / pdf / md / txt / csv 等走文档解析,其他走 BufferedReader)。</p>
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
    /** Skill 文件目录路径（如 ~/.zhiwei/skills），为 null 时 file.read 不支持 skill 参数。 */
    @Nullable
    private final String skillDirectory;
    /** 动态工具注册中心，用于 file.read 的 mcp: 前缀解析。 */
    @Nullable
    private final DynamicToolRegistry toolRegistry;
    /** 附件仓储 —— 用于 file.read 的 attachmentId 分支,Web 未启用时为 null。 */
    @Nullable
    private final AttachmentRepository attachmentRepository;
    /** 文档解析路由 facade —— file.read 的内部依赖,本类自装配,无需外部注入。 */
    private final DocumentParserService documentParserService;

    public FileToolProvider(MetaProperties properties) {
        this(properties, null, null, null, null, null);
    }

    public FileToolProvider(MetaProperties properties,
                            @Nullable FileEditHistory editHistory,
                            @Nullable LintHookExecutor lintHook,
                            @Nullable String skillDirectory) {
        this(properties, editHistory, lintHook, skillDirectory, null, null);
    }

    public FileToolProvider(MetaProperties properties,
                            @Nullable FileEditHistory editHistory,
                            @Nullable LintHookExecutor lintHook,
                            @Nullable String skillDirectory,
                            @Nullable DynamicToolRegistry toolRegistry) {
        this(properties, editHistory, lintHook, skillDirectory, toolRegistry, null);
    }

    /**
     * 完整构造器 —— 支持 file.read 的文档解析路由和对话附件查询。
     *
     * @param properties            元能力配置
     * @param editHistory           文件编辑历史,支持 undo
     * @param lintHook              写入后 lint 回调
     * @param skillDirectory        Skill 目录,null 表示不支持 file.read(skill=...）
     * @param toolRegistry          动态工具注册中心,用于 file.read 的 mcp: 前缀
     * @param attachmentRepository  附件仓储,null 时 file.read(attachmentId=...) 会返回"附件功能未启用"
     */
    public FileToolProvider(MetaProperties properties,
                            @Nullable FileEditHistory editHistory,
                            @Nullable LintHookExecutor lintHook,
                            @Nullable String skillDirectory,
                            @Nullable DynamicToolRegistry toolRegistry,
                            @Nullable AttachmentRepository attachmentRepository) {
        this.properties = properties;
        this.editHistory = editHistory;
        this.lintHook = lintHook;
        this.skillDirectory = skillDirectory;
        this.toolRegistry = toolRegistry;
        this.attachmentRepository = attachmentRepository;
        this.documentParserService = buildDocumentParserService();
    }

    /**
     * 装配 {@link DocumentParserService} —— 使用 Phase 0+1B 的 6 个无状态 parser。
     *
     * <p>parser 实现均无状态、无参构造,直接 new 以避免跨模块 Bean 依赖顺序问题。
     * 顺序:MarkdownParser → PlainTextParser → WordParser → PdfParser
     * → ExcelParser → PowerpointParser,{@code DocumentParserService} 内部
     * 按顺序命中 {@link DocumentParser#canParse}。</p>
     */
    private static DocumentParserService buildDocumentParserService() {
        List<DocumentParser> parsers = List.of(
                new MarkdownParser(),
                new PlainTextParser(),
                new WordParser(),
                new PdfParser(),
                new ExcelParser(),
                new PowerpointParser());
        return new DocumentParserService(parsers);
    }

    /**
     * 构建所有文件工具的 BuiltinTool 列表。
     *
     * @return 文件工具列表
     */
    public List<BuiltinTool> buildFileTools() {
        var tools = new ArrayList<BuiltinTool>();

        // 创建共享的 PathSecurityChecker，避免每个 Executor 重复创建
        var fileConfig = properties.getInfra().getFile();
        var securityChecker = new PathSecurityChecker(fileConfig);
        var fileEditConfig = properties.getInfra().getFileEdit();

        tools.add(buildFileReadTool(
                new FileReadToolExecutor(securityChecker, fileConfig.getDefaultMaxChars(),
                        skillDirectory, toolRegistry, attachmentRepository, documentParserService)));
        tools.add(buildFileWriteTool(
                new FileWriteToolExecutor(securityChecker, editHistory, lintHook, fileEditConfig)));
        tools.add(buildFileListTool(new FileListActionDispatchExecutor(
                new FileListToolExecutor(securityChecker, fileConfig.getDefaultMaxEntries()),
                new FileSearchToolExecutor(securityChecker),
                new FileInfoToolExecutor(securityChecker)
        )));
        tools.add(buildFileEditTool(
                new FilePatchToolExecutor(securityChecker, editHistory, lintHook, fileEditConfig)));
        tools.add(buildFileManageTool(new FileManageActionDispatchExecutor(
                new FileMoveToolExecutor(securityChecker),
                new FileCopyToolExecutor(securityChecker),
                new FileDeleteToolExecutor(securityChecker),
                new FileMkdirToolExecutor(securityChecker)
        )));

        return List.copyOf(tools);
    }

    /** 构建文件读取工具。 */
    private BuiltinTool buildFileReadTool(FileReadToolExecutor executor) {
        var props = new LinkedHashMap<String, Object>();
        props.put("path", Map.of("type", "string",
                "description", "本机文件绝对路径(主入口,与 attachmentId / skill 三选一)。" +
                        "支持所有文本文件;docx / xlsx / pptx / pdf / md / csv 等结构化文档按扩展名自动路由到文档解析器,其他(.java/.txt/.log/.json 等)按纯文本读取。"));
        props.put("attachmentId", Map.of("type", "string",
                "description", "对话附件 ID(与 path / skill 三选一,本机文件优先用 path)。" +
                        "附件仓储未启用时返回错误。"));
        props.put("skill", Map.of("type", "string",
                "description", "加载技能指南:传入 skill ID,多个逗号分隔,最多 3 个(与 path / attachmentId 三选一)"));
        props.put("encoding", Map.of("type", "string",
                "description", "文件编码(如 UTF-8、GBK),默认 UTF-8,仅对纯文本有效"));
        props.put("startLine", Map.of("type", "integer",
                "description", "起始行号(1-based),可选,仅对纯文本有效,结构化文档忽略"));
        props.put("endLine", Map.of("type", "integer",
                "description", "结束行号(1-based),可选,仅对纯文本有效,结构化文档忽略"));
        props.put("maxChars", Map.of("type", "integer",
                "description", "最大返回字符数,默认 30000,超出截断"));

        return BuiltinTool.builder()
                .id("file.read")
                .category(ToolCategory.PERCEPTION)
                .name("读取文件")
                .description("读取文件或加载技能指南。path / attachmentId / skill 三选一:" +
                        "path 读取本机文件(docx / xlsx / pptx / pdf / md / csv 按扩展名自动路由到文档解析,其他按纯文本),"
                        + "attachmentId 读取对话附件(docx / xlsx / pptx / pdf 等自动解析为文本),"
                        + "skill 加载技能(多个逗号分隔)并自动激活技能工具。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", props
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
                        "支持自动创建父目录")
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
                                        "description", "action=list/search 时的最大遍历深度，默认 list=3, search=无限制")),
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

    /** 构建统一文件编辑工具（支持行级操作和文本匹配替换）。 */
    private BuiltinTool buildFileEditTool(FilePatchToolExecutor executor) {
        var itemProperties = new LinkedHashMap<String, Object>();
        itemProperties.put("type", Map.of("type", "string",
                "enum", List.of("insert", "replace", "delete", "search_replace"),
                "description", "操作类型: insert/replace/delete（行级）或 search_replace（文本匹配）"));
        itemProperties.put("line", Map.of("type", "integer",
                "description", "目标行号（1-based），行级操作时必需，所有行号基于原始文件"));
        itemProperties.put("endLine", Map.of("type", "integer",
                "description", "结束行号（replace/delete 时可选，默认等于 line）"));
        itemProperties.put("content", Map.of("type", "string",
                "description", "插入或替换的内容（insert/replace 时必需）"));
        itemProperties.put("oldText", Map.of("type", "string",
                "description", "要查找的文本（search_replace 时必需）"));
        itemProperties.put("newText", Map.of("type", "string",
                "description", "替换后的文本（search_replace 时必需）"));

        var itemSchema = new LinkedHashMap<String, Object>();
        itemSchema.put("type", "object");
        itemSchema.put("required", List.of("type"));
        itemSchema.put("properties", itemProperties);

        return BuiltinTool.builder()
                .id("file.edit")
                .category(ToolCategory.ACTION)
                .name("编辑文件")
                .description("精确修改文件内容（行级操作或文本匹配替换）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path", "operations"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目标文件路径"),
                                "operations", Map.of("type", "array",
                                        "description", "编辑操作列表",
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
                .description("文件和目录的移动、复制、删除、创建")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("move", "copy", "delete", "mkdir"),
                                        "description", "文件管理动作类型")),
                                Map.entry("source", Map.of(
                                        "type", "string",
                                        "description", "源路径；action=move/copy 时必填")),
                                Map.entry("destination", Map.of(
                                        "type", "string",
                                        "description", "目标路径；action=move/copy 时必填")),
                                Map.entry("path", Map.of(
                                        "type", "string",
                                        "description", "目标文件或目录路径；action=delete/mkdir 时必填")),
                                Map.entry("overwrite", Map.of(
                                        "type", "boolean",
                                        "description", "action=move/copy 时目标已存在是否覆盖，默认 false")),
                                Map.entry("recursive", Map.of(
                                        "type", "boolean",
                                        "description", "action=delete 时递归删除目录，action=copy 时递归复制目录，默认 false"))
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
