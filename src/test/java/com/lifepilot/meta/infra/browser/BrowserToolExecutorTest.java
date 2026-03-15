package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 浏览器工具 Executor 单元测试 — 使用 Mock BrowserSessionManager。
 *
 * @author zsg
 * @since 2026-03-08
 */
class BrowserToolExecutorTest {

    // ─────────────────────────────────────────────
    //  优雅降级测试（所有工具共用）
    // ─────────────────────────────────────────────

    @Nested
    class 优雅降级 {

        @Test
        void navigate_sessionManager为null时返回降级提示() {
            var executor = new BrowserNavigateToolExecutor(null, null);
            ToolResult result = executor.execute(buildInput(Map.of("url", "https://example.com")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("Playwright");
        }

        @Test
        void click_sessionManager为null时返回降级提示() {
            var executor = new BrowserClickToolExecutor(null);
            ToolResult result = executor.execute(buildInput(Map.of("selector", "#btn")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("Playwright");
        }

        @Test
        void input_sessionManager为null时返回降级提示() {
            var executor = new BrowserInputToolExecutor(null);
            ToolResult result = executor.execute(buildInput(Map.of("selector", "#name", "value", "test")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("Playwright");
        }

        @Test
        void screenshot_sessionManager为null时返回降级提示() {
            var executor = new BrowserScreenshotToolExecutor(null);
            ToolResult result = executor.execute(buildInput(Map.of()));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("Playwright");
        }

        @Test
        void navigate_sessionManager不可用时返回降级提示() {
            var manager = mock(BrowserSessionManager.class);
            when(manager.isAvailable()).thenReturn(false);
            when(manager.getUnavailableMessage()).thenReturn("浏览器功能未配置，请安装 Playwright");

            var executor = new BrowserNavigateToolExecutor(manager, null);
            ToolResult result = executor.execute(buildInput(Map.of("url", "https://example.com")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("Playwright");
        }
    }
    // ─────────────────────────────────────────────

    @Nested
    class Navigate {

        @Test
        void execute_缺少url参数返回错误() {
            var manager = mockAvailableManager();
            var executor = new BrowserNavigateToolExecutor(manager, null);

            ToolResult result = executor.execute(buildInput(Map.of()));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("url");
        }

        @Test
        void execute_成功导航返回页面信息() {
            var manager = mockAvailableManager();
            var page = mock(PlaywrightPageWrapper.class);
            when(manager.getOrCreatePage("default")).thenReturn(page);
            when(page.navigate("https://example.com")).thenReturn("Example Domain");
            when(page.textContent()).thenReturn("Example Domain body text");
            when(page.url()).thenReturn("https://example.com");

            var executor = new BrowserNavigateToolExecutor(manager, null);
            ToolResult result = executor.execute(buildInput(Map.of("url", "https://example.com")));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("title")).isEqualTo("Example Domain");
            assertThat(result.data().get("url")).isEqualTo("https://example.com");
            assertThat(result.data().get("textSnapshot")).isEqualTo("Example Domain body text");
        }

        @Test
        void execute_自定义sessionId() {
            var manager = mockAvailableManager();
            var page = mock(PlaywrightPageWrapper.class);
            when(manager.getOrCreatePage("my-session")).thenReturn(page);
            when(page.navigate("https://example.com")).thenReturn("Title");
            when(page.textContent()).thenReturn("Body");
            when(page.url()).thenReturn("https://example.com");

            var executor = new BrowserNavigateToolExecutor(manager, null);
            ToolResult result = executor.execute(buildInput(Map.of(
                    "url", "https://example.com",
                    "sessionId", "my-session"
            )));

            assertThat(result.ok()).isTrue();
            verify(manager).getOrCreatePage("my-session");
        }

        @Test
        void execute_导航异常返回错误() {
            var manager = mockAvailableManager();
            when(manager.getOrCreatePage("default")).thenThrow(new RuntimeException("连接超时"));

            var executor = new BrowserNavigateToolExecutor(manager, null);
            ToolResult result = executor.execute(buildInput(Map.of("url", "https://example.com")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("连接超时");
        }
    }

    // ─────────────────────────────────────────────
    //  Click 工具测试
    // ─────────────────────────────────────────────

    @Nested
    class Click {

        @Test
        void execute_缺少selector参数返回错误() {
            var manager = mockAvailableManager();
            var executor = new BrowserClickToolExecutor(manager);

            ToolResult result = executor.execute(buildInput(Map.of()));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("selector");
        }

        @Test
        void execute_成功点击返回结果() {
            var manager = mockAvailableManager();
            var page = mock(PlaywrightPageWrapper.class);
            when(manager.getOrCreatePage("default")).thenReturn(page);
            when(page.url()).thenReturn("https://example.com/clicked");
            when(page.title()).thenReturn("Clicked Page");

            var executor = new BrowserClickToolExecutor(manager);
            ToolResult result = executor.execute(buildInput(Map.of("selector", "#submit-btn")));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("clicked")).isEqualTo("#submit-btn");
            verify(page).click("#submit-btn");
        }
    }

    // ─────────────────────────────────────────────
    //  Input 工具测试
    // ─────────────────────────────────────────────

    @Nested
    class Input {

        @Test
        void execute_缺少selector参数返回错误() {
            var manager = mockAvailableManager();
            var executor = new BrowserInputToolExecutor(manager);

            ToolResult result = executor.execute(buildInput(Map.of("value", "test")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("selector");
        }

        @Test
        void execute_缺少value参数返回错误() {
            var manager = mockAvailableManager();
            var executor = new BrowserInputToolExecutor(manager);

            ToolResult result = executor.execute(buildInput(Map.of("selector", "#name")));

            assertThat(result.ok()).isFalse();
            assertThat(result.error()).contains("value");
        }

        @Test
        void execute_成功填充返回结果() {
            var manager = mockAvailableManager();
            var page = mock(PlaywrightPageWrapper.class);
            when(manager.getOrCreatePage("default")).thenReturn(page);
            when(page.url()).thenReturn("https://example.com/form");

            var executor = new BrowserInputToolExecutor(manager);
            ToolResult result = executor.execute(buildInput(Map.of(
                    "selector", "#username",
                    "value", "testuser"
            )));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("filled")).isEqualTo("#username");
            assertThat(result.data().get("value")).isEqualTo("testuser");
            verify(page).fill("#username", "testuser");
        }
    }

    // ─────────────────────────────────────────────
    //  Screenshot 工具测试
    // ─────────────────────────────────────────────

    @Nested
    class Screenshot {

        @Test
        void execute_成功截图返回Base64() {
            var manager = mockAvailableManager();
            var page = mock(PlaywrightPageWrapper.class);
            when(manager.getOrCreatePage("default")).thenReturn(page);
            when(page.screenshot(false)).thenReturn("iVBORw0KGgoAAAANSUhEUg==");
            when(page.url()).thenReturn("https://example.com");

            var executor = new BrowserScreenshotToolExecutor(manager);
            ToolResult result = executor.execute(buildInput(Map.of()));

            assertThat(result.ok()).isTrue();
            assertThat(result.data().get("screenshot")).isEqualTo("iVBORw0KGgoAAAANSUhEUg==");
            assertThat(result.data().get("fullPage")).isEqualTo(false);
        }

        @Test
        void execute_全页截图() {
            var manager = mockAvailableManager();
            var page = mock(PlaywrightPageWrapper.class);
            when(manager.getOrCreatePage("default")).thenReturn(page);
            when(page.screenshot(true)).thenReturn("fullPageBase64==");
            when(page.url()).thenReturn("https://example.com");

            var executor = new BrowserScreenshotToolExecutor(manager);
            ToolResult result = executor.execute(buildInput(Map.of("fullPage", true)));

            assertThat(result.ok()).isTrue();
            verify(page).screenshot(true);
        }
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private static BrowserSessionManager mockAvailableManager() {
        var manager = mock(BrowserSessionManager.class);
        when(manager.isAvailable()).thenReturn(true);
        return manager;
    }

    private static ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("test.browser", params, JsonSchema.empty(), null);
    }
}
