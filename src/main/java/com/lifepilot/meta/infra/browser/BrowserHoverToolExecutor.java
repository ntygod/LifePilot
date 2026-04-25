package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器悬停工具 — 悬停到指定元素触发 hover 效果。
 *
 * <p>支持两种定位方式（二选一）：</p>
 * <ul>
 *   <li>{@code index}：基于 {@code browser.snapshot} 注入的 {@code data-zhiwei-idx}，抗 layout 抖动</li>
 *   <li>{@code selector}：标准 CSS 选择器</li>
 * </ul>
 *
 * <p>RiskLevel MEDIUM。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserHoverToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserHoverToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserHoverToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public ToolResult execute(ToolInput input) {
        var maybeIndex = input.getOptionalParam("index", Integer.class);
        var maybeSelector = input.getOptionalParam("selector", String.class);

        if (maybeIndex.isPresent() && maybeSelector.isPresent()) {
            return ToolResult.error("index 和 selector 只能二选一");
        }
        if (maybeIndex.isEmpty() && maybeSelector.isEmpty()) {
            return ToolResult.error("缺少必需参数: index 或 selector");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            if (maybeIndex.isPresent()) {
                int idx = maybeIndex.get();
                page.hoverByIndex(idx);
                log.debug("浏览器悬停完成(index): index={}, sessionId={}", idx, sessionId);
                return ToolResult.success(Map.of(
                        "hovered", "index=" + idx,
                        "url", page.url()
                ));
            } else {
                String selector = maybeSelector.get();
                var hoverResult = page.hover(selector);

                var data = new LinkedHashMap<String, Object>();
                data.put("tagName", hoverResult.get("tagName"));
                data.put("textContent", hoverResult.get("textContent"));
                data.put("url", page.url());

                log.debug("浏览器悬停完成(selector): selector={}, sessionId={}", selector, sessionId);
                return ToolResult.success(Map.copyOf(data));
            }
        } catch (Exception e) {
            log.error("浏览器悬停失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器悬停失败: " + e.getMessage());
        }
    }
}
