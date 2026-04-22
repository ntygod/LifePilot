package com.lifepilot.document.tool;

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
 * 文档工具提供者 —— Phase 2B refactor：单 {@code document.create} 工具 + action 路由。
 *
 * <p>早期（Phase 2A/2B 初版）按三种产物独立注册 3 个 BuiltinTool；此处对齐
 * {@code git.mutate} / {@code git.query} 模式，合并为一个工具 + {@code action} 枚举，
 * 由 {@link DocumentCreateActionDispatchExecutor} 分发到底层 docx/xlsx/pptx executor，
 * 减少 LLM 侧 schema 噪声（3 份独立 schema → 1 份扁平 schema）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    /**
     * 产物挂接语义共用后缀 —— 让 LLM 明确：调用后产物会自动挂在当前 assistant 消息附件上，
     * 下游只需把返回的 downloadUrl 告知用户即可，不必自行二次落盘。
     */
    private static final String ATTACHMENT_SUFFIX =
            "产物自动挂到当前 assistant 消息附件上并提供下载 URL。";

    private final DocumentCreateActionDispatchExecutor dispatcher;

    public DocumentToolProvider(DocumentCreateActionDispatchExecutor dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 返回全部文档工具（当前仅单一 {@code document.create}）。
     *
     * <p>保留列表返回形态以便未来若再拆分（例如引入 {@code document.convert}）时无需改签名。</p>
     */
    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildCreateTool());
    }

    /** 构建统一 document.create 工具 —— schema 对齐 git.mutate 扁平模式。 */
    private BuiltinTool buildCreateTool() {
        return BuiltinTool.builder()
                .id("document.create")
                .category(ToolCategory.ACTION)
                .name("生成文档产物")
                .description("根据 action 生成 Word / Excel / PowerPoint 办公产物并保存到本地 documents 目录。" +
                        "action=docx 从 markdown 生成 .docx（支持标题 + 列表 + 段落，不支持表格 / 代码块 / 图片）；" +
                        "action=xlsx 从结构化 sheets 生成 .xlsx（不支持样式 / 公式 / 合并单元格）；" +
                        "action=pptx 从幻灯片大纲生成 .pptx（标题 + 要点 + 可选备注，不支持主题 / 动画 / 图片）。" +
                        ATTACHMENT_SUFFIX)
                .inputSchema(JsonSchema.of(buildCreateSchema()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .actionMetadataFrom(dispatcher)
                .executor(dispatcher)
                .build();
    }

    /** 构建 document.create 输入 schema —— 扁平结构，按 action 分别说明字段用法。 */
    private Map<String, Object> buildCreateSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("docx", "xlsx", "pptx"),
                "description", "文档产物类型：docx=Word 文档，xlsx=Excel 表格，pptx=PowerPoint 幻灯片"
        ));
        properties.put("fileName", Map.of(
                "type", "string",
                "description", "产物文件名（不含扩展名会按 action 自动追加 .docx/.xlsx/.pptx，禁止路径分隔符 / \\ 与 ..）"
        ));
        properties.put("markdown", Map.of(
                "type", "string",
                "description", "action=docx 时必填，文档正文的 Markdown 源。" +
                        "支持一到三级标题（# ## ###）、无序列表（- / *）、有序列表（1. / 2.）和普通段落。" +
                        "当前不支持表格、代码块、内联格式与图片。"
        ));
        properties.put("sheets", Map.of(
                "type", "array",
                "description", "action=xlsx 时必填，工作表列表。每项含 name（工作表名，必需）、" +
                        "headers（表头列表，可选）、rows（数据行二维数组，每行是单元格值列表，单元格可为 string / number / boolean）。",
                "items", Map.of(
                        "type", "object",
                        "required", List.of("name"),
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "headers", Map.of("type", "array", "items", Map.of("type", "string")),
                                "rows", Map.of("type", "array", "items", Map.of("type", "array"))
                        )
                )
        ));
        properties.put("slides", Map.of(
                "type", "array",
                "description", "action=pptx 时必填，幻灯片列表。每项含 title（标题，可选）、" +
                        "bullets（要点列表，必需但可空）、notes（备注，可选）。",
                "items", Map.of(
                        "type", "object",
                        "required", List.of("bullets"),
                        "properties", Map.of(
                                "title", Map.of("type", "string"),
                                "bullets", Map.of("type", "array", "items", Map.of("type", "string")),
                                "notes", Map.of("type", "string")
                        )
                )
        ));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action", "fileName"));
        schema.put("properties", properties);
        return schema;
    }
}
