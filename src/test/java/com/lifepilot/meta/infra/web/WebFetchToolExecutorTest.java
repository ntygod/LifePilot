package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebFetchToolExecutor 单元测试。
 *
 * <p>使用内联 HTML 字符串测试 Jsoup 解析逻辑，不依赖外部网络。
 * 通过子类覆盖 Jsoup.connect 行为来隔离网络调用。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class WebFetchToolExecutorTest {

    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
    }

    @Test
    void 抓取页面_提取正文内容() {
        // 使用可测试的子类，注入预设 HTML
        var executor = new TestableWebFetchToolExecutor(properties,
                """
                <html>
                <head><title>测试页面</title></head>
                <body>
                    <nav>导航栏</nav>
                    <article>这是文章正文内容，包含重要信息。</article>
                    <footer>页脚</footer>
                </body>
                </html>
                """);

        ToolInput input = new ToolInput("builtin.web.fetch",
                Map.of("url", "https://example.com"), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("title")).isEqualTo("测试页面");
        assertThat((String) result.data().get("content")).contains("文章正文内容");
        // nav 和 footer 应被移除
        assertThat((String) result.data().get("content")).doesNotContain("导航栏");
        assertThat((boolean) result.data().get("truncated")).isFalse();
    }

    @Test
    void CSS选择器_提取特定区域() {
        var executor = new TestableWebFetchToolExecutor(properties,
                """
                <html>
                <head><title>选择器测试</title></head>
                <body>
                    <div class="sidebar">侧边栏内容</div>
                    <div class="content">主要内容区域</div>
                    <div class="footer">页脚内容</div>
                </body>
                </html>
                """);

        ToolInput input = new ToolInput("builtin.web.fetch",
                Map.of("url", "https://example.com", "selector", ".content"),
                JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("content")).isEqualTo("主要内容区域");
    }

    @Test
    void CSS选择器_未匹配_返回错误() {
        var executor = new TestableWebFetchToolExecutor(properties,
                "<html><body><p>简单页面</p></body></html>");

        ToolInput input = new ToolInput("builtin.web.fetch",
                Map.of("url", "https://example.com", "selector", ".nonexistent"),
                JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("未匹配到任何元素");
    }

    @Test
    void 内容截断_超过最大长度() {
        // 设置很小的最大内容长度
        properties.getInfra().getWebFetch().setMaxContentLength(20);

        var executor = new TestableWebFetchToolExecutor(properties,
                "<html><head><title>截断测试</title></head><body><article>这是一段很长的文章内容，需要被截断处理以避免超出限制。</article></body></html>");

        ToolInput input = new ToolInput("builtin.web.fetch",
                Map.of("url", "https://example.com"), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((int) result.data().get("contentLength")).isLessThanOrEqualTo(20);
        assertThat((boolean) result.data().get("truncated")).isTrue();
    }

    @Test
    void 缺少url参数_返回错误() {
        var executor = new WebFetchToolExecutor(properties);

        ToolInput input = new ToolInput("builtin.web.fetch",
                Map.of(), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("参数");
    }

    @Test
    void 无article标签_回退到body() {
        var executor = new TestableWebFetchToolExecutor(properties,
                """
                <html>
                <head><title>无语义标签</title></head>
                <body>
                    <p>段落一</p>
                    <p>段落二</p>
                </body>
                </html>
                """);

        ToolInput input = new ToolInput("builtin.web.fetch",
                Map.of("url", "https://example.com"), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((String) result.data().get("content")).contains("段落一");
        assertThat((String) result.data().get("content")).contains("段落二");
    }
}
