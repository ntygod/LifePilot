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
 * 浏览器 JavaScript 执行工具 — 在当前页面上下文中执行 JS 表达式。
 *
 * <p>RiskLevel HIGH — 可执行任意 JavaScript。超时从配置读取。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserEvaluateToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserEvaluateToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;
    private final MetaProperties.Infra.Browser browserConfig;

    public BrowserEvaluateToolExecutor(@Nullable BrowserSessionManager sessionManager,
                                       MetaProperties properties) {
        this.sessionManager = sessionManager;
        this.browserConfig = properties.getInfra().getBrowser();
    }

    /**
     * 执行 JavaScript 表达式。
     *
     * @param input 工具输入，必需参数 expression
     * @return 包含执行结果或错误信息的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        String expression;
        try {
            expression = input.getParam("expression", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: expression");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        long timeoutSeconds = browserConfig.getJsExecutionTimeoutSeconds();

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            String result = page.evaluate(expression, timeoutSeconds);

            var data = new LinkedHashMap<String, Object>();
            data.put("result", result);
            data.put("url", page.url());

            log.debug("浏览器 JS 执行完成: sessionId={}, timeout={}s", sessionId, timeoutSeconds);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // 超时走独立日志等级与返回文案，便于上游区分
            if (msg.contains("超时") || e.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.warn("浏览器 JS 执行超时: sessionId={}, timeout={}s", sessionId, timeoutSeconds);
                return ToolResult.error("JS 执行超过 " + timeoutSeconds + " 秒超时");
            }
            log.error("浏览器 JS 执行失败: sessionId={}, error={}", sessionId, msg, e);
            return ToolResult.error("浏览器 JS 执行失败: " + msg);
        }
    }
}
