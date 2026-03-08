package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Map;

/**
 * 可测试的 WebFetchToolExecutor — 使用预设 HTML 替代网络请求。
 *
 * <p>测试专用，通过注入 HTML 字符串避免真实网络调用。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class TestableWebFetchToolExecutor {

    private final MetaProperties properties;
    private final String html;

    TestableWebFetchToolExecutor(MetaProperties properties, String html) {
        this.properties = properties;
        this.html = html;
    }

    /**
     * 执行 Web 页面抓取（使用预设 HTML）。
     */
    public ToolResult execute(ToolInput input) {
        try {
            String url = input.getParam("url", String.class);
            var selector = input.getOptionalParam("selector", String.class);
            int maxContentLength = properties.getInfra().getWebFetch().getMaxContentLength();

            // 使用预设 HTML 解析，不发起网络请求
            Document doc = Jsoup.parse(html);

            String title = doc.title();
            String content;
            boolean truncated = false;

            if (selector.isPresent() && !selector.get().isBlank()) {
                Element selected = doc.selectFirst(selector.get());
                if (selected == null) {
                    return ToolResult.error(
                            "CSS 选择器 '%s' 未匹配到任何元素".formatted(selector.get()));
                }
                content = selected.text();
            } else {
                content = extractMainContent(doc);
            }

            if (content.length() > maxContentLength) {
                content = content.substring(0, maxContentLength);
                truncated = true;
            }

            return ToolResult.success(Map.of(
                    "title", title,
                    "url", url,
                    "content", content,
                    "contentLength", content.length(),
                    "truncated", truncated
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Web 抓取异常: " + e.getMessage());
        }
    }

    /** 提取页面主体正文（复制自 WebFetchToolExecutor 的逻辑）。 */
    private String extractMainContent(Document doc) {
        doc.select("script, style, nav, header, footer, aside, .sidebar, .menu, .nav").remove();

        String[] mainSelectors = {"article", "main", "[role=main]", ".content", "#content", ".post-content"};
        for (String sel : mainSelectors) {
            Element main = doc.selectFirst(sel);
            if (main != null && !main.text().isBlank()) {
                return main.text();
            }
        }

        Element body = doc.body();
        return body != null ? body.text() : doc.text();
    }
}
