package com.lifepilot.document.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentToolProvider 单工具契约测试 —— Phase 2B refactor 后。
 *
 * <p>覆盖点：</p>
 * <ul>
 *   <li>{@code buildDocumentTools()} 仅返回 1 个工具，id 为 {@code document.create}</li>
 *   <li>schema 扁平结构：required=[action, fileName]；properties 含 action/fileName/markdown/sheets/slides</li>
 *   <li>action 枚举为 [docx, xlsx, pptx]</li>
 *   <li>markdown / sheets / slides 字段 description 分别带 "action=docx" / "action=xlsx" / "action=pptx" 提示</li>
 *   <li>{@code actionMetadata()} 通过 dispatcher 暴露 3 条（docx/xlsx/pptx）</li>
 *   <li>风险等级 MEDIUM、category ACTION、tags 含 infrastructure+document、description 含附件挂接语义</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentToolProvider_单工具契约测试 {

    @Mock DocumentCreateDocxToolExecutor docxExecutor;
    @Mock DocumentCreateXlsxToolExecutor xlsxExecutor;
    @Mock DocumentCreatePptxToolExecutor pptxExecutor;

    private DocumentToolProvider provider;

    @BeforeEach
    void setUp() {
        var dispatcher = new DocumentCreateActionDispatchExecutor(docxExecutor, xlsxExecutor, pptxExecutor);
        provider = new DocumentToolProvider(dispatcher);
    }

    @Test
    void 返回单个工具_id_为_document_create() {
        List<BuiltinTool> tools = provider.buildDocumentTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).id()).isEqualTo("document.create");
    }

    @Test
    void schema_required_为_action_和_fileName() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        assertThat(tool.inputSchema().requiredFields())
                .containsExactlyInAnyOrder("action", "fileName");
    }

    @Test
    void schema_properties_含_action_fileName_markdown_sheets_slides() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) tool.inputSchema().toMap().get("properties");

        assertThat(properties).containsKeys("action", "fileName", "markdown", "sheets", "slides");
    }

    @Test
    void action_字段_enum_为_docx_xlsx_pptx() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) tool.inputSchema().toMap().get("properties");
        @SuppressWarnings("unchecked")
        var action = (Map<String, Object>) properties.get("action");

        assertThat(action).containsEntry("type", "string");
        assertThat(action.get("enum"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("docx", "xlsx", "pptx");
    }

    @Test
    void 字段_description_按_action_场景分别说明_markdown_sheets_slides() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) tool.inputSchema().toMap().get("properties");
        @SuppressWarnings("unchecked")
        var markdown = (Map<String, Object>) properties.get("markdown");
        @SuppressWarnings("unchecked")
        var sheets = (Map<String, Object>) properties.get("sheets");
        @SuppressWarnings("unchecked")
        var slides = (Map<String, Object>) properties.get("slides");

        assertThat((String) markdown.get("description")).contains("action=docx");
        assertThat((String) sheets.get("description")).contains("action=xlsx");
        assertThat((String) slides.get("description")).contains("action=pptx");
    }

    @Test
    void actionMetadata_含三条_docx_xlsx_pptx_且均为_MEDIUM() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        assertThat(tool.actionMetadata()).hasSize(3);
        assertThat(tool.actionMetadata()).containsKeys("docx", "xlsx", "pptx");
        tool.actionMetadata().forEach((action, meta) ->
                assertThat(meta.riskLevel()).isEqualTo(RiskLevel.MEDIUM));
    }

    @Test
    void 工具分组_ACTION_风险_MEDIUM_idempotent_false_executor_非空() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        assertThat(tool.category()).isEqualTo(ToolCategory.ACTION);
        assertThat(tool.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(tool.idempotent()).isFalse();
        assertThat(tool.executor()).isNotNull();
    }

    @Test
    void tags_含_infrastructure_和_document() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        assertThat(tool.tags()).contains("infrastructure", "document");
    }

    @Test
    void description_含附件挂接语义_与三类产物提示() {
        BuiltinTool tool = provider.buildDocumentTools().get(0);

        // 附件挂接语义让 LLM 明确无需二次落盘
        assertThat(tool.description()).contains("挂到当前 assistant 消息附件");
        // description 必须展开 3 类 action 提示，LLM 才能按需选参
        assertThat(tool.description()).contains("action=docx");
        assertThat(tool.description()).contains("action=xlsx");
        assertThat(tool.description()).contains("action=pptx");
    }
}
