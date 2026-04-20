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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentToolProvider 三工具装配契约测试。
 *
 * <p>覆盖点：</p>
 * <ul>
 *   <li>{@code buildDocumentTools()} 返回 3 个工具且 id 精确对齐</li>
 *   <li>每个工具的 required 字段对齐（docx/xlsx/pptx 各自的输入约束）</li>
 *   <li>风险等级、工具分组、executor、description 语义一致性</li>
 *   <li>{@code getTool(String)} 能按 id 查询；未知 id 返回 null（不抛）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentToolProvider_三工具装配测试 {

    @Mock DocumentCreateDocxToolExecutor docxExecutor;
    @Mock DocumentCreateXlsxToolExecutor xlsxExecutor;
    @Mock DocumentCreatePptxToolExecutor pptxExecutor;

    private DocumentToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DocumentToolProvider(docxExecutor, xlsxExecutor, pptxExecutor);
    }

    @Test
    void 返回三个工具且_id_分别对齐_docx_xlsx_pptx() {
        List<BuiltinTool> tools = provider.buildDocumentTools();

        assertThat(tools).hasSize(3);
        assertThat(tools)
                .extracting(BuiltinTool::id)
                .containsExactly(
                        "document.create_docx",
                        "document.create_xlsx",
                        "document.create_pptx"
                );
    }

    @Test
    void docx_工具_required_字段对齐_fileName_markdown() {
        BuiltinTool docx = provider.getTool("document.create_docx");

        assertThat(docx).isNotNull();
        assertThat(docx.inputSchema().requiredFields())
                .containsExactlyInAnyOrder("fileName", "markdown");
    }

    @Test
    void xlsx_工具_required_字段对齐_fileName_sheets() {
        BuiltinTool xlsx = provider.getTool("document.create_xlsx");

        assertThat(xlsx).isNotNull();
        assertThat(xlsx.inputSchema().requiredFields())
                .containsExactlyInAnyOrder("fileName", "sheets");
    }

    @Test
    void pptx_工具_required_字段对齐_fileName_slides() {
        BuiltinTool pptx = provider.getTool("document.create_pptx");

        assertThat(pptx).isNotNull();
        assertThat(pptx.inputSchema().requiredFields())
                .containsExactlyInAnyOrder("fileName", "slides");
    }

    @Test
    void 三工具风险等级均为_MEDIUM_分组均为_ACTION_executor_均非空() {
        List<BuiltinTool> tools = provider.buildDocumentTools();

        assertThat(tools)
                .allSatisfy(tool -> {
                    assertThat(tool.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
                    assertThat(tool.category()).isEqualTo(ToolCategory.ACTION);
                    assertThat(tool.executor()).isNotNull();
                });
    }

    @Test
    void 三工具_description_均包含_挂到_assistant_消息附件_语义() {
        List<BuiltinTool> tools = provider.buildDocumentTools();

        // 共用后缀保证 LLM 在选择任一工具时都看到"产物会自动挂附件"语义
        assertThat(tools)
                .allSatisfy(tool -> assertThat(tool.description())
                        .contains("挂到当前 assistant 消息附件"));
    }

    @Test
    void getTool_按_id_查询_命中返回实例_未知返回_null() {
        // 命中
        assertThat(provider.getTool("document.create_xlsx"))
                .isNotNull()
                .extracting(BuiltinTool::id)
                .isEqualTo("document.create_xlsx");

        // 未知：返回 null（不抛，保持幂等查询语义；是否抛由调用方决定）
        assertThat(provider.getTool("document.create_unknown")).isNull();
        assertThat(provider.getTool("")).isNull();
        assertThat(provider.getTool(null)).isNull();
    }
}
