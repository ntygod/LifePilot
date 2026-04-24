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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrowserInputToolExecutor} index 与 selector 双路径测试。
 *
 * <p>验证 index/selector 二选一语义，value 始终必需：</p>
 * <ul>
 *   <li>index+value → 走 {@link PlaywrightPageWrapper#inputByIndex(int, String)}</li>
 *   <li>selector+value → 走 {@link PlaywrightPageWrapper#fill(String, String)}</li>
 *   <li>两者都传 / 都不传 / 缺 value → 返回参数错误</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class BrowserInputToolExecutor_index路径测试 {

    @Mock BrowserSessionManager sessionManager;
    @Mock PlaywrightPageWrapper page;

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("browser", params, JsonSchema.empty(), null, null);
    }

    @Test
    void 传_index_和_value_走_inputByIndex_不调用_fill() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.url()).thenReturn("https://example.com/form");

        var exec = new BrowserInputToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("index", 3, "value", "hello")));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.data().get("filled")).isEqualTo("index=3");
        assertThat(r.data().get("value")).isEqualTo("hello");
        verify(page).inputByIndex(3, "hello");
        verify(page, never()).fill(anyString(), anyString());
    }

    @Test
    void 传_selector_和_value_走_fill_不调用_inputByIndex() {
        when(sessionManager.getOrCreatePage("default")).thenReturn(page);
        when(page.url()).thenReturn("https://example.com/form");

        var exec = new BrowserInputToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("selector", "#name", "value", "world")));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.data().get("filled")).isEqualTo("#name");
        assertThat(r.data().get("value")).isEqualTo("world");
        verify(page).fill("#name", "world");
        verify(page, never()).inputByIndex(anyInt(), any());
    }

    @Test
    void 同时传_index_和_selector_返回参数错误() {
        var exec = new BrowserInputToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("index", 1, "selector", "#x", "value", "v")));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).contains("二选一");
    }

    @Test
    void 都不传_index_和_selector_返回参数错误() {
        var exec = new BrowserInputToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("value", "v")));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).contains("index 或 selector");
    }

    @Test
    void 有_index_缺_value_返回参数错误() {
        var exec = new BrowserInputToolExecutor(sessionManager);
        ToolResult r = exec.execute(input(Map.of("index", 1)));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).contains("value");
    }
}
