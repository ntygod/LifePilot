package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrowserEvaluateToolExecutor} 超时消费测试。
 *
 * <p>验证配置的 {@code jsExecutionTimeoutSeconds} 真正传给
 * {@link PlaywrightPageWrapper#evaluate(String, long)}，并且超时时返回友好错误。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class BrowserEvaluateToolExecutor_超时测试 {

    @Test
    void 正常执行时返回JS结果和URL() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        var page = mock(PlaywrightPageWrapper.class);
        when(manager.getOrCreatePage("default")).thenReturn(page);
        when(page.evaluate(eq("1+1"), anyLong())).thenReturn("2");
        when(page.url()).thenReturn("https://example.com");

        var executor = new BrowserEvaluateToolExecutor(manager, mockMetaProperties());
        ToolResult result = executor.execute(buildInput(Map.of("expression", "1+1")));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("result", "2");
    }

    @Test
    void 配置的超时秒数传给了PageWrapper() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        var page = mock(PlaywrightPageWrapper.class);
        when(manager.getOrCreatePage("default")).thenReturn(page);
        when(page.evaluate(eq("42"), anyLong())).thenReturn("42");
        when(page.url()).thenReturn("about:blank");

        var executor = new BrowserEvaluateToolExecutor(manager, mockMetaProperties());
        executor.execute(buildInput(Map.of("expression", "42")));

        // 配置 10 秒必须真正传给 page.evaluate
        verify(page).evaluate("42", 10L);
    }

    @Test
    void 超时时返回错误而不是抛异常() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        var page = mock(PlaywrightPageWrapper.class);
        when(manager.getOrCreatePage("default")).thenReturn(page);
        // 让 page.evaluate 抛超时异常
        when(page.evaluate(eq("while(true){}"), anyLong()))
                .thenThrow(new RuntimeException("JS 执行超过 10 秒超时"));

        var executor = new BrowserEvaluateToolExecutor(manager, mockMetaProperties());
        ToolResult result = executor.execute(buildInput(Map.of("expression", "while(true){}")));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("超时");
    }

    @Test
    void 缺少expression参数返回错误() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        var executor = new BrowserEvaluateToolExecutor(manager, mockMetaProperties());

        ToolResult result = executor.execute(buildInput(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("expression");
    }

    // ─────────────────────────────────────────────
    private static ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("test.browser", params, JsonSchema.empty(), null, null);
    }

    private static MetaProperties mockMetaProperties() {
        var props = mock(MetaProperties.class, RETURNS_DEEP_STUBS);
        when(props.getInfra().getBrowser().getJsExecutionTimeoutSeconds()).thenReturn(10);
        when(props.getInfra().getBrowser().getAccessibilityMaxDepth()).thenReturn(5);
        return props;
    }
}
