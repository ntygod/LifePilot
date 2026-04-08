package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器下拉选择工具 — 从 select 元素中选择选项。
 *
 * <p>RiskLevel MEDIUM。支持按 value 或 label 选择。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserSelectToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserSelectToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserSelectToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public ToolResult execute(ToolInput input) {
        String selector;
        try {
            selector = input.getParam("selector", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: selector");
        }

        var valueOpt = input.getOptionalParam("value", String.class);
        var labelOpt = input.getOptionalParam("label", String.class);

        if (valueOpt.isEmpty() && labelOpt.isEmpty()) {
            return ToolResult.error("参数 value 和 label 至少提供一个");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            boolean byLabel = labelOpt.isPresent();
            String selectValue = byLabel ? labelOpt.get() : valueOpt.get();
            var selectResult = page.selectOption(selector, selectValue, byLabel);

            var data = new LinkedHashMap<String, Object>();
            data.put("selectedValue", selectResult.get("selectedValue"));
            data.put("selectedLabel", selectResult.get("selectedLabel"));
            data.put("url", page.url());

            log.debug("浏览器下拉选择完成: selector={}, sessionId={}", selector, sessionId);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器下拉选择失败: selector={}, sessionId={}, error={}", selector, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器下拉选择失败: " + e.getMessage());
        }
    }
}
