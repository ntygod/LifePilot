package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器输入工具 — 使用 Playwright 填充表单字段。
 *
 * <p>RiskLevel MEDIUM。当 {@link BrowserSessionManager} 不可用时返回优雅降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserInputToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserInputToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserInputToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 填充表单字段。
     *
     * @param input 工具输入，必需参数 selector 和 value，可选 sessionId
     * @return 包含操作结果的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String selector;
        String value;
        try {
            selector = input.getParam("selector", String.class);
            value = input.getParam("value", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: " + e.getMessage());
        }

        String sessionId = input.getOptionalParam("sessionId", String.class)
                .orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            page.fill(selector, value);

            log.debug("浏览器输入完成: selector={}, sessionId={}", selector, sessionId);
            return ToolResult.success(Map.of(
                    "filled", selector,
                    "value", value,
                    "url", page.url()
            ));
        } catch (Exception e) {
            log.error("浏览器输入失败: selector={}, sessionId={}, error={}", selector, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器输入失败: " + e.getMessage());
        }
    }
}
