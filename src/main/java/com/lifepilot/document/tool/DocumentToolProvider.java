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
 * 文档工具提供者 —— Phase 2A 含 document.create_docx。
 *
 * <p>Phase 0 曾因工具合并而清理此类，Phase 2A 重建用于承载 create_* 工具族。
 * Phase 2B 将扩展 create_xlsx / create_pptx。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    private final DocumentCreateDocxToolExecutor createDocxExecutor;

    public DocumentToolProvider(DocumentCreateDocxToolExecutor createDocxExecutor) {
        this.createDocxExecutor = createDocxExecutor;
    }

    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildCreateDocxTool());
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
}
