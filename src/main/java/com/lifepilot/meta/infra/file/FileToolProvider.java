package com.lifepilot.meta.infra.file;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.schema.JsonSchema;

import java.util.ArrayList;
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

        return List.copyOf(tools);
    }

    /** 构建文件读取工具。 */
    private BuiltinTool buildFileReadTool(FileReadToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.file.read")
                .name("读取文件")
                .description("读取指定路径的文件内容，支持编码指定。超过最大读取大小时自动截断")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "文件路径"),
                                "encoding", Map.of("type", "string",
                                        "description", "文件编码（如 UTF-8、GBK），默认 UTF-8")
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
                .description("列出指定目录的文件和子目录，支持深度限制和 glob 模式过滤")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("path"),
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "目录路径"),
                                "maxDepth", Map.of("type", "integer",
                                        "description", "最大遍历深度，默认 3"),
                                "pattern", Map.of("type", "string",
                                        "description", "glob 过滤模式（如 *.java），可选")
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
                .description("递归搜索目录下文件内容，支持正则表达式匹配和 glob 文件名过滤")
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
                                        "description", "最大返回结果数，默认 50")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
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
