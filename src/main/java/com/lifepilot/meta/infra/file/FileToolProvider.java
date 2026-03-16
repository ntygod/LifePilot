package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.schema.JsonSchema;

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

    public FileToolProvider(MetaProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建所有文件工具的 BuiltinTool 列表。
     *
     * @return 文件工具列表
     */
    public List<BuiltinTool> buildFileTools() {
        var tools = new ArrayList<BuiltinTool>();

        tools.add(buildFileReadTool(new FileReadToolExecutor(properties)));
        tools.add(buildFileWriteTool(new FileWriteToolExecutor(properties)));
        tools.add(buildFileListTool(new FileListToolExecutor(properties)));
        tools.add(buildFileSearchTool(new FileSearchToolExecutor(properties)));
        tools.add(buildFileAppendTool(new FileAppendToolExecutor(properties)));
        tools.add(buildFileDeleteTool(new FileDeleteToolExecutor(properties)));
        tools.add(buildFileCopyTool(new FileCopyToolExecutor(properties)));
        tools.add(buildFileMoveTool(new FileMoveToolExecutor(properties)));
        tools.add(buildFileInfoTool(new FileInfoToolExecutor(properties)));
        tools.add(buildFilePatchTool(new FilePatchToolExecutor(properties)));

        return List.copyOf(tools);
    }

    /** 构建文件读取工具。 */
    private BuiltinTool buildFileReadTool(FileReadToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.read")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件写入工具。 */
    private BuiltinTool buildFileWriteTool(FileWriteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.write")
                .name("写入文件")
                .description("原子写入文件内容（先写临时文件再重命名），支持自动创建父目录。MEDIUM 风险")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path", "content"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目标文件路径"),
                                "content", Map.of("type", "string",
                                        "description", "要写入的文件内容"),
                                "createDirectories", Map.of("type", "boolean",
                                        "description", "父目录不存在时是否自动创建，默认 true")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件列表工具。 */
    private BuiltinTool buildFileListTool(FileListToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.list")
                .name("列出目录")
                .description("列出指定目录的文件和子目录，支持深度限制、glob 过滤、maxEntries 截断和目录优先排序")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件搜索工具。 */
    private BuiltinTool buildFileSearchTool(FileSearchToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.search")
                .name("搜索文件内容")
                .description("递归搜索目录下文件内容，支持正则表达式、glob 过滤、上下文行和二进制文件自动跳过")
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
                                "contextLines", Map.of("type", "integer",
                                        "description", "匹配行前后上下文行数，默认 0")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
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
                .id("builtin.file.patch")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件信息工具。 */
    private BuiltinTool buildFileInfoTool(FileInfoToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.info")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件移动工具。 */
    private BuiltinTool buildFileMoveTool(FileMoveToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.move")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件复制工具。 */
    private BuiltinTool buildFileCopyTool(FileCopyToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.copy")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件删除工具。 */
    private BuiltinTool buildFileDeleteTool(FileDeleteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.delete")
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
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建文件追加工具。 */
    private BuiltinTool buildFileAppendTool(FileAppendToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.append")
                .name("追加文件")
                .description("向文件末尾追加内容，文件不存在时自动创建。MEDIUM 风险")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path", "content"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目标文件路径"),
                                "content", Map.of("type", "string",
                                        "description", "要追加的内容")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }
}
