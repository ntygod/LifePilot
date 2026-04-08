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

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            String result = page.evaluate(expression);

            var data = new LinkedHashMap<String, Object>();
            data.put("result", result);
            data.put("url", page.url());

            log.debug("浏览器 JS 执行完成: sessionId={}, timeout={}s", sessionId,
                    browserConfig.getJsExecutionTimeoutSeconds());
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器 JS 执行失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            var errorData = new LinkedHashMap<String, Object>();
            errorData.put("error", e.getMessage());
            errorData.put("stackTrace", e.toString());
            return ToolResult.error("浏览器 JS 执行失败: " + e.getMessage());
        }
    }
}
