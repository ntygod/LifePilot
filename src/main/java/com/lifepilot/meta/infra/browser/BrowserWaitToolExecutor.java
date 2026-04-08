package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器等待工具 — 等待指定元素出现或状态变化。
 *
 * <p>RiskLevel LOW，幂等操作。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserWaitToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserWaitToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;
    private final MetaProperties.Infra.Browser browserConfig;

    public BrowserWaitToolExecutor(@Nullable BrowserSessionManager sessionManager, MetaProperties properties) {
        this.sessionManager = sessionManager;
        this.browserConfig = properties.getInfra().getBrowser();
    }

    public ToolResult execute(ToolInput input) {
        String selector;
        try {
            selector = input.getParam("selector", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: selector");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
        String state = input.getOptionalParam("state", String.class).orElse("visible");
        int timeout = input.getOptionalParam("timeout", Integer.class)
                .orElse(browserConfig.getWaitTimeoutSeconds());

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            page.waitForSelector(selector, state, timeout * 1000);

            log.debug("浏览器等待完成: selector={}, state={}, sessionId={}", selector, state, sessionId);
            return ToolResult.success(Map.of(
                    "found", true,
                    "selector", selector,
                    "state", state,
                    "url", page.url()
            ));
        } catch (Exception e) {
            log.error("浏览器等待失败: selector={}, sessionId={}, error={}", selector, sessionId, e.getMessage(), e);
            return ToolResult.error("等待超时或失败: selector=%s, state=%s, timeout=%ds — %s"
                    .formatted(selector, state, timeout, e.getMessage()));
        }
    }
}
