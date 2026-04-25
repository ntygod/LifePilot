package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrowserHoverToolExecutor} index 与 selector 双路径测试。
 *
 * <p>验证 index/selector 二选一语义：</p>
 * <ul>
 *   <li>仅 index → 走 {@link PlaywrightPageWrapper#hoverByIndex(int)}</li>
 *   <li>仅 selector → 走 {@link PlaywrightPageWrapper#hover(String)}</li>
 *   <li>两者都传 / 都不传 → 返回参数错误</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class BrowserHoverToolExecutor_index路径测试 {

    @Mock BrowserSessionManager sessionManager;
    @Mock PlaywrightPageWrapper page;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("browser", params, JsonSchema.empty(), null, null);
    }

    @Test
    void 传_index_走_hoverByIndex_不调用_hover() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.url()).thenReturn("https://example.com");

        var exec = new BrowserHoverToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("index", 7)));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.data().get("hovered")).isEqualTo("index=7");
        verify(page).hoverByIndex(7);
        verify(page, never()).hover(any());
    }

    @Test
    void 传_selector_走_hover_不调用_hoverByIndex() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.url()).thenReturn("https://example.com");
        when(page.hover("#menu")).thenReturn(Map.of("tagName", "DIV", "textContent", "菜单"));

        var exec = new BrowserHoverToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("selector", "#menu")));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.data().get("tagName")).isEqualTo("DIV");
        assertThat(r.data().get("textContent")).isEqualTo("菜单");
        verify(page).hover("#menu");
        verify(page, never()).hoverByIndex(anyInt());
    }

    @Test
    void 同时传_index_和_selector_返回参数错误() {
        var exec = new BrowserHoverToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("index", 1, "selector", "#x")));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).contains("二选一");
    }

    @Test
    void 都不传_返回参数错误() {
        var exec = new BrowserHoverToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of()));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).contains("index 或 selector");
    }
}
