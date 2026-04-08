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
        String selector;
        try {
            selector = input.getParam("selector", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: selector");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            var hoverResult = page.hover(selector);

            var data = new LinkedHashMap<String, Object>();
            data.put("tagName", hoverResult.get("tagName"));
            data.put("textContent", hoverResult.get("textContent"));
            data.put("url", page.url());

            log.debug("浏览器悬停完成: selector={}, sessionId={}", selector, sessionId);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器悬停失败: selector={}, sessionId={}, error={}", selector, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器悬停失败: " + e.getMessage());
        }
    }
}
