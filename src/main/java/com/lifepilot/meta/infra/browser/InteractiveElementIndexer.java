package com.lifepilot.meta.infra.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * DOM 可交互元素扫描器。
 *
 * <p>启动时从 classpath 加载 JS 脚本 {@code interactive-elements.js}，
 * 每次 index 调用时通过 {@link Page#evaluate} 注入执行，解析返回 JSON 为
 * {@link IndexedSnapshot}。扫描同时给每个命中元素注入 {@code data-zhiwei-idx}
 * 属性，供后续 {@code clickByIndex} 等通过稳定选择器定位。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class InteractiveElementIndexer {

    private static final Logger log = LoggerFactory.getLogger(InteractiveElementIndexer.class);
    private static final String SCRIPT_PATH = "static/browser-scripts/interactive-elements.js";

    private final ObjectMapper objectMapper;
    private final String script;

    public InteractiveElementIndexer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.script = loadScript();
    }

    private String loadScript() {
        try (var is = new ClassPathResource(SCRIPT_PATH).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 DOM 标号脚本: " + SCRIPT_PATH, e);
        }
    }

    /**
     * 扫描页面可交互元素并注入编号。
     *
     * @param page         Playwright Page
     * @param injectLabels 是否叠加红色视觉编号标签
     * @param maxElements  最大返回数，超出部分仍计入 total
     */
    public IndexedSnapshot index(Page page, boolean injectLabels, int maxElements) {
        Object opts = Map.of("injectLabels", injectLabels, "maxElements", maxElements);
        Object raw = page.evaluate(script, opts);
        if (!(raw instanceof Map<?, ?> map)) {
            log.warn("DOM 标号脚本返回值非 Map: {}", raw);
            return new IndexedSnapshot(List.of(), 0, new int[]{0, 0}, false);
        }
        try {
            String json = objectMapper.writeValueAsString(map);
            var tree = objectMapper.readTree(json);
            List<IndexedElement> elements = objectMapper.convertValue(
                    tree.get("elements"),
                    new TypeReference<List<IndexedElement>>() {}
            );
            int total = tree.get("total").asInt();
            var viewportNode = tree.get("viewport");
            int[] viewport = {viewportNode.get(0).asInt(), viewportNode.get(1).asInt()};
            boolean truncated = tree.get("truncated").asBoolean();
            return new IndexedSnapshot(elements, total, viewport, truncated);
        } catch (Exception e) {
            log.error("解析 DOM 标号结果失败", e);
            return new IndexedSnapshot(List.of(), 0, new int[]{0, 0}, false);
        }
    }
}
