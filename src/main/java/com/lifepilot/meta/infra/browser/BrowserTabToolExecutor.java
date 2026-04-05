package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器标签页管理工具 — 支持打开、切换、关闭和列出标签页。
 *
 * <p>RiskLevel MEDIUM。委托 {@link BrowserSessionManager} 的多标签页方法。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserTabToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserTabToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserTabToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 执行标签页管理操作。
     *
     * @param input 工具输入，必需参数 tabAction（open/switch/close/list），兼容旧参数 action；可选 url、tabId
     * @return 包含操作结果的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        String action = input.getOptionalParam("tabAction", String.class)
                .or(() -> input.getOptionalParam("action", String.class))
                .orElse(null);
        if (action == null || action.isBlank()) {
            return ToolResult.error("缺少必需参数: tabAction");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            return switch (action) {
                case "open" -> handleOpen(input, sessionId);
                case "switch" -> handleSwitch(input, sessionId);
                case "close" -> handleClose(input, sessionId);
                case "list" -> handleList(sessionId);
                default -> ToolResult.error("不支持的 action: " + action
                        + "，可选值: open, switch, close, list");
            };
        } catch (Exception e) {
            log.error("浏览器标签页操作失败: action={}, sessionId={}, error={}",
                    action, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器标签页操作失败: " + e.getMessage());
        }
    }

    private ToolResult handleOpen(ToolInput input, String sessionId) {
        var urlOpt = input.getOptionalParam("url", String.class);
        String url = urlOpt.orElse("about:blank");

        String tabId = sessionManager.openNewPage(sessionId, url);
        var page = sessionManager.getOrCreatePage(sessionId);

        var data = new LinkedHashMap<String, Object>();
        data.put("tabId", tabId);
        data.put("url", page.url());
        data.put("title", page.title());

        log.debug("浏览器新标签页打开: tabId={}, sessionId={}", tabId, sessionId);
        return ToolResult.success(Map.copyOf(data));
    }

    private ToolResult handleSwitch(ToolInput input, String sessionId) {
        String tabId;
        try {
            tabId = input.getParam("tabId", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("switch 操作缺少必需参数: tabId");
        }

        sessionManager.switchPage(sessionId, tabId);
        var page = sessionManager.getOrCreatePage(sessionId);

        var data = new LinkedHashMap<String, Object>();
        data.put("tabId", tabId);
        data.put("url", page.url());
        data.put("title", page.title());

        log.debug("浏览器标签页切换: tabId={}, sessionId={}", tabId, sessionId);
        return ToolResult.success(Map.copyOf(data));
    }

    private ToolResult handleClose(ToolInput input, String sessionId) {
        String tabId;
        try {
            tabId = input.getParam("tabId", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("close 操作缺少必需参数: tabId");
        }

        sessionManager.closeTab(sessionId, tabId);

        log.debug("浏览器标签页关闭: tabId={}, sessionId={}", tabId, sessionId);
        return ToolResult.success(Map.of("closed", tabId));
    }

    private ToolResult handleList(String sessionId) {
        var tabs = sessionManager.listPages(sessionId);
        var tabList = tabs.stream()
                .map(t -> Map.<String, Object>of(
                        "tabId", t.tabId(),
                        "url", t.url(),
                        "title", t.title()))
                .toList();

        log.debug("浏览器标签页列表: count={}, sessionId={}", tabList.size(), sessionId);
        return ToolResult.success(Map.of("tabs", tabList));
    }
}
