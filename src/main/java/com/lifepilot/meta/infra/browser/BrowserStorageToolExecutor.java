package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器存储管理工具 — 操作 Cookie 和 localStorage。
 *
 * <p>RiskLevel MEDIUM。支持 get/set/clear 操作，target 区分 cookie 和 localStorage。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserStorageToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserStorageToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserStorageToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 执行存储操作。
     *
     * @param input 工具输入，必需参数 target（cookie/localStorage）、action（get/set/clear）
     * @return 包含操作结果的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        String target;
        String action;
        try {
            target = input.getParam("target", String.class);
            action = input.getParam("action", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: target 和 action");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            return switch (target) {
                case "cookie" -> handleCookie(page, action, input, sessionId);
                case "localStorage" -> handleLocalStorage(page, action, input, sessionId);
                default -> ToolResult.error("不支持的 target: " + target
                        + "，可选值: cookie, localStorage");
            };
        } catch (Exception e) {
            log.error("浏览器存储操作失败: target={}, action={}, sessionId={}, error={}",
                    target, action, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器存储操作失败: " + e.getMessage());
        }
    }

    private ToolResult handleCookie(PlaywrightPageWrapper page, String action,
                                    ToolInput input, String sessionId) {
        return switch (action) {
            case "get" -> {
                var cookies = page.getCookies();
                log.debug("浏览器 Cookie 获取: count={}, sessionId={}", cookies.size(), sessionId);
                yield ToolResult.success(Map.of("cookies", cookies));
            }
            case "set" -> {
                String name;
                String value;
                try {
                    name = input.getParam("name", String.class);
                    value = input.getParam("value", String.class);
                } catch (IllegalArgumentException e) {
                    yield ToolResult.error("cookie set 操作缺少必需参数: name 和 value");
                }
                var domain = input.getOptionalParam("domain", String.class).orElse(null);
                var path = input.getOptionalParam("path", String.class).orElse(null);
                page.setCookie(name, value, domain, path);
                log.debug("浏览器 Cookie 设置: name={}, sessionId={}", name, sessionId);
                yield ToolResult.success(Map.of("set", name));
            }
            case "clear" -> {
                page.clearCookies();
                log.debug("浏览器 Cookie 清除: sessionId={}", sessionId);
                yield ToolResult.success(Map.of("cleared", true));
            }
            default -> ToolResult.error("不支持的 cookie action: " + action
                    + "，可选值: get, set, clear");
        };
    }

    private ToolResult handleLocalStorage(PlaywrightPageWrapper page, String action,
                                          ToolInput input, String sessionId) {
        return switch (action) {
            case "get" -> {
                String key;
                try {
                    key = input.getParam("name", String.class);
                } catch (IllegalArgumentException e) {
                    yield ToolResult.error("localStorage get 操作缺少必需参数: name");
                }
                String value = page.getLocalStorage(key);
                log.debug("浏览器 localStorage 获取: key={}, sessionId={}", key, sessionId);
                yield ToolResult.success(Map.of("key", key, "value", value != null ? value : ""));
            }
            case "set" -> {
                String key;
                String value;
                try {
                    key = input.getParam("name", String.class);
                    value = input.getParam("value", String.class);
                } catch (IllegalArgumentException e) {
                    yield ToolResult.error("localStorage set 操作缺少必需参数: name 和 value");
                }
                page.setLocalStorage(key, value);
                log.debug("浏览器 localStorage 设置: key={}, sessionId={}", key, sessionId);
                yield ToolResult.success(Map.of("key", key, "value", value));
            }
            case "clear" -> {
                page.clearLocalStorage();
                log.debug("浏览器 localStorage 清除: sessionId={}", sessionId);
                yield ToolResult.success(Map.of("cleared", true));
            }
            default -> ToolResult.error("不支持的 localStorage action: " + action
                    + "，可选值: get, set, clear");
        };
    }
}
