package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.browser.PlaywrightPageWrapper;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WebFetchToolExecutor 浏览器渲染回退单元测试。
 *
 * <p>验证 Jsoup 静态抓取内容不足时自动回退到 Playwright 浏览器渲染的逻辑，
 * 以及 {@code renderJs} 参数强制浏览器渲染和浏览器不可用时的降级行为。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
@ExtendWith(MockitoExtension.class)
class WebFetchBrowserFallbackTest {

    private MetaProperties properties;

    @Mock
    private BrowserSessionManager browserSessionManager;

    @Mock
    private PlaywrightPageWrapper pageWrapper;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
    }

    @Test
    void execute_静态内容足够时不触发浏览器渲染() {
        // 设置较低的阈值，确保内容足够
        properties.getInfra().getWebFetch().setMinStaticContentLength(10);

        // 使用 TestableWebFetchWithBrowser 注入预设 HTML，内容远超阈值
        var executor = new TestableWebFetchWithBrowser(properties, browserSessionManager,
                """
                <html>
                <head><title>静态页面</title></head>
                <body>
                    <article>这是一段足够长的静态内容，不需要浏览器渲染来获取。包含重要的文章信息。</article>
                </body>
                </html>
                """);

        ToolInput input = new ToolInput("web.fetch",
                Map.of("url", "https://example.com"),
                JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("renderMode")).isEqualTo("static");
        assertThat((String) result.data().get("content")).contains("足够长的静态内容");
        // 浏览器不应被调用
        verifyNoInteractions(browserSessionManager);
    }

    @Test
    void execute_renderJs参数为true时强制浏览器渲染() {
        // 配置浏览器可用
        when(browserSessionManager.isAvailable()).thenReturn(true);
        when(browserSessionManager.getOrCreatePage(anyString())).thenReturn(pageWrapper);
        when(pageWrapper.navigateWithResult(anyString(), anyInt()))
                .thenReturn(new PlaywrightPageWrapper.NavigateResult("JS 渲染页面", "https://spa-example.com", false));
        when(pageWrapper.textContent()).thenReturn("这是通过浏览器渲染获取的动态内容，包含 JavaScript 加载的数据。");
        when(pageWrapper.evaluate(anyString())).thenReturn("\"complete\"");

        var executor = new WebFetchToolExecutor(properties, browserSessionManager);

        ToolInput input = new ToolInput("web.fetch",
                Map.of("url", "https://spa-example.com", "renderJs", true),
                JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("renderMode")).isEqualTo("browser");
        assertThat((String) result.data().get("content")).contains("浏览器渲染获取的动态内容");
        assertThat(result.data().get("title")).isEqualTo("JS 渲染页面");
        // 验证浏览器会话创建（成功时不主动关闭，由空闲超时清理）
        verify(browserSessionManager).getOrCreatePage(anyString());
        verify(browserSessionManager, never()).closePage(anyString());
    }

    @Test
    void execute_浏览器不可用时回退Jsoup结果() {
        // 配置浏览器不可用
        when(browserSessionManager.isAvailable()).thenReturn(false);

        // 使用 TestableWebFetchWithBrowser 注入较短的静态 HTML
        // 内容短于阈值但因浏览器不可用，应仍然返回 Jsoup 结果
        properties.getInfra().getWebFetch().setMinStaticContentLength(10000);

        var executor = new TestableWebFetchWithBrowser(properties, browserSessionManager,
                """
                <html>
                <head><title>JS应用</title></head>
                <body>
                    <div id="app">加载中...</div>
                </body>
                </html>
                """);

        ToolInput input = new ToolInput("web.fetch",
                Map.of("url", "https://spa-example.com"),
                JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        // 即使内容短，浏览器不可用时应返回静态结果
        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("renderMode")).isEqualTo("static");
        assertThat((String) result.data().get("content")).contains("加载中");
        // 不应尝试创建浏览器会话
        verify(browserSessionManager, never()).getOrCreatePage(anyString());
    }

    @Test
    void execute_静态内容过短且浏览器可用时触发回退() {
        // 设置高阈值，让静态内容必然低于阈值
        properties.getInfra().getWebFetch().setMinStaticContentLength(10000);

        // 配置浏览器可用
        when(browserSessionManager.isAvailable()).thenReturn(true);
        when(browserSessionManager.getOrCreatePage(anyString())).thenReturn(pageWrapper);
        when(pageWrapper.navigateWithResult(anyString(), anyInt()))
                .thenReturn(new PlaywrightPageWrapper.NavigateResult("动态页面", "https://spa.example.com", false));
        when(pageWrapper.textContent()).thenReturn("浏览器渲染后获取的完整内容，原静态抓取为空。");
        when(pageWrapper.evaluate(anyString())).thenReturn("\"complete\"");

        var executor = new TestableWebFetchWithBrowser(properties, browserSessionManager,
                """
                <html>
                <head><title>SPA</title></head>
                <body><div id="root"></div></body>
                </html>
                """);

        ToolInput input = new ToolInput("web.fetch",
                Map.of("url", "https://spa.example.com"),
                JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("renderMode")).isEqualTo("browser");
        assertThat((String) result.data().get("content")).contains("浏览器渲染后获取的完整内容");
        verify(browserSessionManager).getOrCreatePage(anyString());
        verify(browserSessionManager, never()).closePage(anyString());
    }

    /**
     * 可测试的 WebFetchToolExecutor 子类 — 注入预设 HTML 并支持浏览器回退。
     *
     * <p>通过继承覆盖 Jsoup 网络调用，同时保留浏览器回退能力。</p>
     */
    private static class TestableWebFetchWithBrowser extends WebFetchToolExecutor {

        private final String html;
        private final MetaProperties properties;

        TestableWebFetchWithBrowser(MetaProperties properties,
                                    BrowserSessionManager browserSessionManager,
                                    String html) {
            super(properties, browserSessionManager);
            this.properties = properties;
            this.html = html;
        }

        @Override
        public ToolResult execute(ToolInput input) {
            try {
                String url = input.getParam("url", String.class);
                var selector = input.getOptionalParam("selector", String.class);
                var renderJs = input.getOptionalParam("renderJs", Boolean.class);

                var config = properties.getInfra().getWebFetch();
                boolean forceRenderJs = renderJs.isPresent() && renderJs.get();

                // 强制浏览器渲染时委托父类
                if (forceRenderJs) {
                    return super.execute(input);
                }

                // 使用预设 HTML 解析
                var doc = org.jsoup.Jsoup.parse(html);
                String title = doc.title();
                String content;

                if (selector.isPresent() && !selector.get().isBlank()) {
                    var selected = doc.selectFirst(selector.get());
                    if (selected == null) {
                        return ToolResult.error("CSS 选择器 '%s' 未匹配到任何元素".formatted(selector.get()));
                    }
                    content = selected.text();
                } else {
                    doc.select("script, style, nav, header, footer, aside, .sidebar, .menu, .nav").remove();
                    String[] mainSelectors = {"article", "main", "[role=main]", ".content", "#content", ".post-content"};
                    content = "";
                    for (String sel : mainSelectors) {
                        var main = doc.selectFirst(sel);
                        if (main != null && !main.text().isBlank()) {
                            content = main.text();
                            break;
                        }
                    }
                    if (content.isEmpty()) {
                        var body = doc.body();
                        content = body != null ? body.text() : "";
                    }
                }

                // 检查是否需要浏览器回退（委托到父类中的 browser 逻辑）
                int minStaticContentLength = config.getMinStaticContentLength();
                if (content.length() < minStaticContentLength) {
                    // 尝试使用反射调用父类的 fetchWithBrowser
                    // 改为直接走父类的 execute（renderJs=true 路径）
                    try {
                        var method = WebFetchToolExecutor.class.getDeclaredMethod(
                                "isBrowserAvailable");
                        method.setAccessible(true);
                        boolean browserAvailable = (boolean) method.invoke(this);
                        if (browserAvailable) {
                            var fetchMethod = WebFetchToolExecutor.class.getDeclaredMethod(
                                    "fetchWithBrowser", String.class, String.class,
                                    MetaProperties.Infra.WebFetch.class);
                            fetchMethod.setAccessible(true);
                            return (ToolResult) fetchMethod.invoke(this, url,
                                    selector.orElse(null), config);
                        }
                    } catch (Exception e) {
                        // 反射失败时静默回退
                    }
                }

                boolean truncated = false;
                int maxContentLength = config.getMaxContentLength();
                if (content.length() > maxContentLength) {
                    content = content.substring(0, maxContentLength);
                    truncated = true;
                }

                return ToolResult.success(Map.of(
                        "title", title,
                        "url", url,
                        "content", content,
                        "contentLength", content.length(),
                        "truncated", truncated,
                        "renderMode", "static"
                ));

            } catch (IllegalArgumentException e) {
                return ToolResult.error("参数错误: " + e.getMessage());
            } catch (Exception e) {
                return ToolResult.error("Web 抓取异常: " + e.getMessage());
            }
        }
    }
}
