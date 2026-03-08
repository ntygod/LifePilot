package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器点击工具 — 使用 Playwright 点击指定 CSS 选择器的元素。
 *
 * <p>RiskLevel MEDIUM。当 {@link BrowserSessionManager} 不可用时返回优雅降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserClickToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserClickToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserClickToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 点击指定选择器的元素。
     *
     * @param input 工具输入，必需参数 selector，可选 sessionId
     * @return 包含操作结果的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        String selector;
        try {
            selector = input.getParam("selector", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: selector");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class)
                .orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            page.click(selector);

            log.debug("浏览器点击完成: selector={}, sessionId={}", selector, sessionId);
            return ToolResult.success(Map.of(
                    "clicked", selector,
                    "url", page.url(),
                    "title", page.title()
            ));
        } catch (Exception e) {
            log.error("浏览器点击失败: selector={}, sessionId={}, error={}", selector, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器点击失败: " + e.getMessage());
        }
    }
}
