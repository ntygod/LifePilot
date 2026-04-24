package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrowserAccessibilityToolExecutor} 的 maxDepth 参数透传测试。
 *
 * <p>验证配置值 {@code accessibilityMaxDepth}
 * 真正传递给 {@link PlaywrightPageWrapper#accessibilitySnapshot(String, int)}，
 * 以及入参覆盖配置默认值的逻辑。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class BrowserAccessibilityToolExecutor_maxDepth消费测试 {

    @Test
    void 无入参时使用配置默认maxDepth() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        var page = mock(PlaywrightPageWrapper.class);
        when(manager.getOrCreatePage("default")).thenReturn(page);
        when(page.accessibilitySnapshot(isNull(), eq(5))).thenReturn("- body: ...");
        when(page.url()).thenReturn("about:blank");

        var executor = new BrowserAccessibilityToolExecutor(manager, mockMetaProperties(5));
        ToolResult result = executor.execute(buildInput(Map.of()));

        assertThat(result.ok()).isTrue();
        verify(page).accessibilitySnapshot(null, 5);
    }

    @Test
    void 入参maxDepth覆盖配置默认值() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        var page = mock(PlaywrightPageWrapper.class);
        when(manager.getOrCreatePage("default")).thenReturn(page);
        when(page.accessibilitySnapshot(eq("main"), eq(2))).thenReturn("- main: ...");
        when(page.url()).thenReturn("about:blank");

        var executor = new BrowserAccessibilityToolExecutor(manager, mockMetaProperties(5));
        ToolResult result = executor.execute(buildInput(Map.of(
                "rootSelector", "main",
                "maxDepth", 2
        )));

        assertThat(result.ok()).isTrue();
        verify(page).accessibilitySnapshot("main", 2);
    }

    // ─────────────────────────────────────────────
    private static ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("test.browser", params, JsonSchema.empty(), null, null);
    }

    private static MetaProperties mockMetaProperties(int maxDepth) {
        var props = mock(MetaProperties.class, RETURNS_DEEP_STUBS);
        when(props.getInfra().getBrowser().getAccessibilityMaxDepth()).thenReturn(maxDepth);
        return props;
    }
}
