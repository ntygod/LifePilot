package com.lifepilot.document.tool;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentCreateActionDispatchExecutor 路由测试。
 *
 * <p>覆盖点：</p>
 * <ul>
 *   <li>action=docx / xlsx / pptx 各自正确路由到对应底层 executor</li>
 *   <li>action 未知时返回 "不支持的操作" 错误，底层 executor 均不调用</li>
 *   <li>缺少 action 参数时返回参数校验错误，底层 executor 均不调用</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-20
 */
@ExtendWith(MockitoExtension.class)
class DocumentCreateActionDispatchExecutor_路由测试 {

    @Mock DocumentCreateDocxToolExecutor docxExecutor;
    @Mock DocumentCreateXlsxToolExecutor xlsxExecutor;
    @Mock DocumentCreatePptxToolExecutor pptxExecutor;

    private DocumentCreateActionDispatchExecutor dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DocumentCreateActionDispatchExecutor(docxExecutor, xlsxExecutor, pptxExecutor);
    }

    @Test
    void action_为_docx_时路由到_docx_executor() {
        when(docxExecutor.execute(any())).thenReturn(ToolResult.success(Map.of("documentId", "doc-d")));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "docx",
                "fileName", "报告",
                "markdown", "# 标题"
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-d");
        verify(docxExecutor).execute(any());
        verify(xlsxExecutor, never()).execute(any());
        verify(pptxExecutor, never()).execute(any());
    }

    @Test
    void action_为_xlsx_时路由到_xlsx_executor() {
        when(xlsxExecutor.execute(any())).thenReturn(ToolResult.success(Map.of("documentId", "doc-x")));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "xlsx",
                "fileName", "报表",
                "sheets", java.util.List.of()
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-x");
        verify(xlsxExecutor).execute(any());
        verify(docxExecutor, never()).execute(any());
        verify(pptxExecutor, never()).execute(any());
    }

    @Test
    void action_为_pptx_时路由到_pptx_executor() {
        when(pptxExecutor.execute(any())).thenReturn(ToolResult.success(Map.of("documentId", "doc-p")));

        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "pptx",
                "fileName", "幻灯",
                "slides", java.util.List.of()
        )));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-p");
        verify(pptxExecutor).execute(any());
        verify(docxExecutor, never()).execute(any());
        verify(xlsxExecutor, never()).execute(any());
    }

    @Test
    void action_未知时返回_不支持的操作_错误_底层_executor_均不调用() {
        ToolResult result = dispatcher.execute(newInput(Map.of(
                "action", "txt",
                "fileName", "随便"
        )));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("不支持的操作");
        assertThat(result.error()).contains("txt");
        verify(docxExecutor, never()).execute(any());
        verify(xlsxExecutor, never()).execute(any());
        verify(pptxExecutor, never()).execute(any());
    }

    @Test
    void 缺少_action_参数时返回参数错误_底层_executor_均不调用() {
        // HashMap 允许缺 action key（Map.of 不允许空值，用 HashMap 明确表达"字段缺失"）
        Map<String, Object> params = new HashMap<>();
        params.put("fileName", "随便");

        ToolResult result = dispatcher.execute(newInput(params));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("action");
        verify(docxExecutor, never()).execute(any());
        verify(xlsxExecutor, never()).execute(any());
        verify(pptxExecutor, never()).execute(any());
    }

    // 默认注入 sess-1 context, 与底层 executor 测试保持一致（此处 mock executor 不读 context, 但保持约定）
    private ToolInput newInput(Map<String, Object> params) {
        return newInput(params, Map.of("sessionId", "sess-1"));
    }

    private ToolInput newInput(Map<String, Object> params, Map<String, Object> context) {
        return new ToolInput("document.create", params,
                JsonSchema.of(Map.of("type", "object")), null, context);
    }
}
