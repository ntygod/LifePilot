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
 * document.edit 工具提供者 —— 单工具 + 5 种 action 的扁平 schema。
 *
 * <p>对齐 Phase 2B {@link DocumentToolProvider}：action ∈
 * {patch, diff, commit, rollback, list_versions}，由
 * {@link DocumentEditActionDispatchExecutor} 分发到
 * {@link com.lifepilot.document.version.DocumentVersionService} 对应方法。</p>
 *
 * <p>schema 只把 {@code action} 列为 JSON Schema 层面的 required；其余字段按
 * action 值在运行时柔性必填（分发器内做具体校验），避免 LLM 侧 schema 冗余。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocumentEditToolProvider {

    private static final List<String> EDIT_TAGS = List.of("infrastructure", "document", "edit", "docx", "xlsx", "patch", "diff", "commit", "rollback", "version");

    private final DocumentEditActionDispatchExecutor dispatcher;

    public DocumentEditToolProvider(DocumentEditActionDispatchExecutor dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 构建统一 document.edit 工具 —— schema 对齐 git.mutate / document.create 扁平模式。 */
    public BuiltinTool buildEditTool() {
        return BuiltinTool.builder()
                .id("document.edit")
                .category(ToolCategory.ACTION)
                .name("编辑文档工作副本")
                .description(buildDescription())
                .inputSchema(JsonSchema.of(buildSchema()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(EDIT_TAGS)
                .actionMetadataFrom(dispatcher)
                .executor(dispatcher)
                .build();
    }

    /** 工具 description —— 英文短描述。 */
    private String buildDescription() {
        return "Edit an existing docx or xlsx working copy via anchor-based patches with version management. action=patch applies incremental edits; action=diff inspects pending changes; action=commit finalizes the working copy to disk; action=rollback reverts to a previous version; action=list_versions enumerates version history.";
    }

    /** 构建 document.edit 输入 schema —— 扁平结构，按 action 分别说明字段用法。 */
    private Map<String, Object> buildSchema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("patch", "diff", "commit", "rollback", "list_versions"),
                "description", "操作类型：patch=施加一批编辑 op；diff=取 diff JSON；" +
                        "commit=提交工作副本；rollback=回滚到历史版本；list_versions=列版本历史"
        ));
        properties.put("source", Map.of(
                "type", "object",
                "description", "patch action 必填；" +
                        "type ∈ {path, attachment, document}；" +
                        "path 用 value=绝对路径，attachment/document 用 id=资源 ID",
                "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of("path", "attachment", "document")),
                        "value", Map.of("type", "string"),
                        "id", Map.of("type", "string")
                )
        ));
        properties.put("operations", Map.of(
                "type", "array",
                "description", "patch action 必填；每个 op 形如 {op, ...字段, reason?}。" +
                        "【docx op】op ∈ {replace_text, insert_paragraph_after, delete_paragraph, add_table_row, set_paragraph_style}；" +
                        "replace_text 需 before_context + target + after_context + new_text；" +
                        "insert_paragraph_after 需 anchor_paragraph_text + new_paragraphs（{text, style?}[]）；" +
                        "delete_paragraph 需 paragraph_text；" +
                        "add_table_row 需 table_anchor_text + position(start/end：start 插到表头，end 追到表尾) + cells(string[])；" +
                        "set_paragraph_style 需 anchor_text + new_style（docx styles.xml 里的 style id，如 Heading1/Heading2/Normal/Title）。" +
                        "【xlsx op】op ∈ {update_cell, insert_row, delete_row, set_range}；" +
                        "update_cell 需 sheet + cell + new_value（多态：number/boolean/string，= 开头为公式；null 清空）；" +
                        "insert_row 需 sheet + before_row（1-based 行号） + values(array)；" +
                        "delete_row 需 sheet + row（1-based）；" +
                        "set_range 需 sheet + range（A1，如 B2:D4）+ values（2D array，外长=行数，内长=列数）。" +
                        "同批 operations 不能跨 MIME 混用（即要么全是 docx op，要么全是 xlsx op）。",
                "items", Map.of("type", "object")
        ));
        properties.put("documentId", Map.of(
                "type", "string",
                "description", "diff / commit / rollback / list_versions action 必填；" +
                        "首次 patch 成功后从返回值获取"
        ));
        properties.put("from", Map.of(
                "type", "integer",
                "description", "diff action 的起始版本号（含）"
        ));
        properties.put("to", Map.of(
                "type", "integer",
                "description", "diff action 的目标版本号（含）"
        ));
        properties.put("commitTarget", Map.of(
                "type", "string",
                "enum", List.of("overwrite", "saveAs"),
                "description", "commit action 必填：overwrite=覆盖原路径（生成 .bak 备份）；saveAs=另存到新路径"
        ));
        properties.put("saveAsPath", Map.of(
                "type", "string",
                "description", "commit + saveAs 时必填，目标路径（绝对路径）"
        ));
        properties.put("userConfirmation", Map.of(
                "type", "string",
                "enum", List.of("overwrite", "saveAs"),
                "description", "commit action 必填：只有用户明确同意（说「确认/是的/覆盖/另存/可以」等）" +
                        "后才能带此字段，值必须精确等于 commitTarget。禁止在没征询用户的情况下擅自填写 —— " +
                        "commit 是写用户本地文件的破坏性操作，默认行为应是先 reply 用户求确认再调用。"
        ));
        properties.put("version", Map.of(
                "type", "integer",
                "description", "rollback action 必填，目标版本号"
        ));

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));
        schema.put("properties", properties);
        return schema;
    }
}
