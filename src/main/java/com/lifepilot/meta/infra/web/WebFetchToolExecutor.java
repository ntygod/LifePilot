package com.lifepilot.meta.infra.web;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * Web 抓取工具执行器 — 使用 Jsoup 解析 HTML 并提取正文内容。
 *
 * <p>支持通过 CSS 选择器提取特定区域内容，超时和内容长度均可配置。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class WebFetchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebFetchToolExecutor.class);

    private final MetaProperties properties;

    public WebFetchToolExecutor(MetaProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行 Web 页面抓取。
     *
     * @param input 工具输入，必需参数 url，可选参数 selector（CSS 选择器）
     * @return 包含页面标题和正文内容的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String url = input.getParam("url", String.class);
            var selector = input.getOptionalParam("selector", String.class);

            var config = properties.getInfra().getWebFetch();
            int timeoutMillis = config.getTimeoutSeconds() * 1000;
            int maxContentLength = config.getMaxContentLength();

            Document doc = Jsoup.connect(url)
                    .timeout(timeoutMillis)
                    .userAgent("LifePilot/1.0 (Web Fetch Tool)")
                    .followRedirects(true)
                    .get();

            String title = doc.title();
            String content;
            boolean truncated = false;

            if (selector.isPresent() && !selector.get().isBlank()) {
                // 使用 CSS 选择器提取特定区域
                Element selected = doc.selectFirst(selector.get());
                if (selected == null) {
                    return ToolResult.error(
                            "CSS 选择器 '%s' 未匹配到任何元素".formatted(selector.get()));
                }
                content = selected.text();
            } else {
                // 提取页面主体正文
                content = extractMainContent(doc);
            }

            // 截断超长内容
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
        } catch (SocketTimeoutException e) {
            log.warn("Web 抓取超时: {}", e.getMessage());
            return ToolResult.error("请求超时（%d 秒），请稍后重试"
                    .formatted(properties.getInfra().getWebFetch().getTimeoutSeconds()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (IOException e) {
            log.error("Web 抓取失败: {}", e.getMessage(), e);
            return ToolResult.error("Web 抓取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web 抓取异常: {}", e.getMessage(), e);
            return ToolResult.error("Web 抓取异常: " + e.getMessage());
        }
    }

    /**
     * 提取页面主体正文。
     *
     * <p>优先尝试 {@code <article>}、{@code <main>}、{@code [role=main]} 等语义标签，
     * 回退到 {@code <body>} 全文。</p>
     */
    private String extractMainContent(Document doc) {
        // 移除脚本、样式、导航等非正文元素
        doc.select("script, style, nav, header, footer, aside, .sidebar, .menu, .nav").remove();

        // 优先尝试语义标签
        String[] mainSelectors = {"article", "main", "[role=main]", ".content", "#content", ".post-content"};
        for (String sel : mainSelectors) {
            Element main = doc.selectFirst(sel);
            if (main != null && !main.text().isBlank()) {
                return main.text();
            }
        }

        // 回退到 body 全文
        Element body = doc.body();
        return body != null ? body.text() : doc.text();
    }
}
