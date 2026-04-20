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
 * 文档工具提供者 —— 当前阶段仅含 document.parse。
 *
 * <p>后续 Phase 将扩展 document.create_*、document.patch_* 等工具，
 * 都通过本 Provider 集中构建。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    private final DocumentParseToolExecutor parseExecutor;

    public DocumentToolProvider(DocumentParseToolExecutor parseExecutor) {
        this.parseExecutor = parseExecutor;
    }

    /**
     * 构建所有文档工具的 BuiltinTool 列表。
     *
     * @return 文档工具列表
     */
    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildParseTool());
    }

    /** 构建 document.parse 工具。 */
    private BuiltinTool buildParseTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("attachmentId", Map.of("type", "string",
                "description", "对话附件 ID（与 path 二选一）。优先使用此参数。"));
        properties.put("path", Map.of("type", "string",
                "description", "本地文件路径（与 attachmentId 二选一）"));
        properties.put("maxChars", Map.of("type", "integer",
                "description", "返回内容最大字符数，默认 30000，超出截断；返回的 totalChars 为原始文本字符数（截断前）"));

        return BuiltinTool.builder()
                .id("document.parse")
                .category(ToolCategory.PERCEPTION)
                .name("解析文档")
                .description("解析 docx / pdf / md / txt 等文档为可读文本。" +
                        "用户上传 docx/pdf 附件后可调此工具读取内容。" +
                        "attachmentId 和 path 二选一。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", properties
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("path")
                ))
                .tags(DOCUMENT_TAGS)
                .executor(parseExecutor::execute)
                .build();
    }
}
