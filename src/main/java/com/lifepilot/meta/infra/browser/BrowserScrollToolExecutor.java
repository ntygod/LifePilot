package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器滚动工具 — 滚动页面或滚动到指定元素。
 *
 * <p>RiskLevel MEDIUM。支持方向滚动和元素定位滚动。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserScrollToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserScrollToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;
    private final MetaProperties.Infra.Browser browserConfig;

    public BrowserScrollToolExecutor(@Nullable BrowserSessionManager sessionManager, MetaProperties properties) {
        this.sessionManager = sessionManager;
        this.browserConfig = properties.getInfra().getBrowser();
    }

    public ToolResult execute(ToolInput input) {
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
        var selectorOpt = input.getOptionalParam("selector", String.class);

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            ScrollResult result;

            if (selectorOpt.isPresent()) {
                result = page.scrollToElement(selectorOpt.get());
            } else {
                String direction = input.getOptionalParam("direction", String.class).orElse("down");
                int pixels = input.getOptionalParam("pixels", Integer.class)
                        .orElse(browserConfig.getDefaultScrollPixels());
                result = page.scroll(direction, pixels);
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("scrollX", result.scrollX());
            data.put("scrollY", result.scrollY());
            data.put("url", page.url());

            log.debug("浏览器滚动完成: sessionId={}", sessionId);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器滚动失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器滚动失败: " + e.getMessage());
        }
    }
}
