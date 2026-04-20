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
 * 文档工具提供者 —— Phase 2B 含 document.create_docx / create_xlsx / create_pptx。
 *
 * <p>Phase 0 曾因工具合并清理，Phase 2A 重建仅含 docx，Phase 2B 扩展至 3 种办公格式。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    private final DocumentCreateDocxToolExecutor createDocxExecutor;
    private final DocumentCreateXlsxToolExecutor createXlsxExecutor;
    private final DocumentCreatePptxToolExecutor createPptxExecutor;

    public DocumentToolProvider(DocumentCreateDocxToolExecutor createDocxExecutor,
                                DocumentCreateXlsxToolExecutor createXlsxExecutor,
                                DocumentCreatePptxToolExecutor createPptxExecutor) {
        this.createDocxExecutor = createDocxExecutor;
        this.createXlsxExecutor = createXlsxExecutor;
        this.createPptxExecutor = createPptxExecutor;
    }

    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildCreateDocxTool(), buildCreateXlsxTool(), buildCreatePptxTool());
    }

    private BuiltinTool buildCreateDocxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .docx，禁止路径分隔符 / \\ 与 ..）"));
        properties.put("markdown", Map.of("type", "string",
                "description", "文档正文的 Markdown 源。支持一到三级标题（# ## ###）、" +
                        "无序列表（- / *）、有序列表（1. / 2.）和普通段落。" +
                        "当前不支持表格、代码块、内联格式与图片。"));

        return BuiltinTool.builder()
                .id("document.create_docx")
                .category(ToolCategory.ACTION)
                .name("生成 Word 文档")
                .description("从 markdown 生成 Word 文档（.docx）并保存到本地 documents 目录。" +
                        "产物自动挂到当前 assistant 消息附件上并提供下载 URL。" +
                        "适用于生成周报 / 报告 / 简短方案等不要求复杂排版的文档。" +
                        "产物样式为基础级（标题 + 段落 + 列表，无表格）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "markdown"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createDocxExecutor::execute)
                .build();
    }

    private BuiltinTool buildCreateXlsxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .xlsx，禁止路径分隔符 / \\ 与 ..）"));
        properties.put("sheets", Map.of(
                "type", "array",
                "description", "工作表列表。每项含 name（工作表名，必需）、headers（表头列表，可选）、" +
                        "rows（数据行二维数组，每行是单元格值列表，单元格可为 string / number / boolean）。",
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

        return BuiltinTool.builder()
                .id("document.create_xlsx")
                .category(ToolCategory.ACTION)
                .name("生成 Excel 表格")
                .description("从结构化数据生成 Excel 表格（.xlsx）并保存到本地 documents 目录。" +
                        "适用于数据报表 / 台账 / 对账单。不支持样式 / 公式 / 合并单元格。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "sheets"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createXlsxExecutor::execute)
                .build();
    }

    private BuiltinTool buildCreatePptxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .pptx，禁止路径分隔符 / \\ 与 ..）"));
        properties.put("slides", Map.of(
                "type", "array",
                "description", "幻灯片列表。每项含 title（标题，可选）、bullets（要点列表，必需但可空）、" +
                        "notes（备注，可选）。",
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

        return BuiltinTool.builder()
                .id("document.create_pptx")
                .category(ToolCategory.ACTION)
                .name("生成 PowerPoint 幻灯片")
                .description("从幻灯片大纲生成 PowerPoint（.pptx）并保存到本地 documents 目录。" +
                        "每张幻灯片含标题 + 要点列表 + 可选备注。不支持主题 / 动画 / 图片 / 图表。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "slides"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createPptxExecutor::execute)
                .build();
    }
}
