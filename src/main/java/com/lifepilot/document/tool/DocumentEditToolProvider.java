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

    private static final List<String> EDIT_TAGS = List.of("infrastructure", "document", "edit");

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

    /** 工具 description —— 嵌入 spec §7.3 LLM 用法要点。 */
    private String buildDescription() {
        return "对 docx 文档施加文本锚点编辑并管理版本。" +
                "action=patch 提交一批 operations（文本锚点事务性替换/插入段落/删除段落/追加表格行），" +
                "locator 需在文档中唯一匹配，整批成败一致（任一 op 定位失败则全部回滚），保留原 run 样式；" +
                "action=diff 拉取某次 patch 的 diff JSON；" +
                "action=commit 把工作副本覆盖回源路径（overwrite，自动生成 .bak 备份）或另存到新路径（saveAs）；" +
                "action=rollback 回滚到指定历史版本（生成新版本而非物理撤销历史）；" +
                "action=list_versions 列出文档的版本历史。" +
                "LLM 用法要点：" +
                "(1) 先用 file.read 读到文档内容再规划 locator，不要凭空猜测文本；" +
                "(2) before_context / after_context 建议各带 10-30 字以提高 locator 唯一性，过短易命中多处；" +
                "(3) 单次 patch 内可传入多个 operations，它们以事务方式整批生效；" +
                "(4) commit 是用户动作，LLM 不应主动 commit，应把 downloadUrl 告知用户由其决定是否落盘；" +
                "(5) 所有 op 都保留原 run 样式（字体 / 字号 / 颜色 / 粗斜体），" +
                "new_text 里不要再自行拼装样式标记。";
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
                "description", "patch action 必填；每个 op 形如 {op, ...字段, reason?}；" +
                        "op ∈ {replace_text, insert_paragraph_after, delete_paragraph, add_table_row}；" +
                        "字段组合：" +
                        "replace_text 需 before_context + target + after_context + new_text；" +
                        "insert_paragraph_after 需 anchor_paragraph_text + new_paragraphs（{text, style?}[]）；" +
                        "delete_paragraph 需 paragraph_text；" +
                        "add_table_row 需 table_anchor_text + position(first/last) + cells(string[])",
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
