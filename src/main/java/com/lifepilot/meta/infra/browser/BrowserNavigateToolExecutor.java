package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器导航工具 — 使用 Playwright 导航到指定 URL 并返回页面快照。
 *
 * <p>RiskLevel MEDIUM。当 {@link BrowserSessionManager} 不可用时返回优雅降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserNavigateToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserNavigateToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserNavigateToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 导航到指定 URL，返回页面标题和文本快照。
     *
     * @param input 工具输入，必需参数 url，可选 sessionId
     * @return 包含 title、url、textSnapshot 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        // 检查 Playwright 可用性
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        // 提取参数
        String url;
        try {
            url = input.getParam("url", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数: url");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class)
                .orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            String title = page.navigate(url);
            String textSnapshot = page.textContent();

            // 截断过长的文本快照
            if (textSnapshot != null && textSnapshot.length() > 10000) {
                textSnapshot = textSnapshot.substring(0, 10000) + "...[文本已截断]";
            }

            var data = new LinkedHashMap<String, Object>();
            data.put("title", title != null ? title : "");
            data.put("url", page.url());
            data.put("textSnapshot", textSnapshot != null ? textSnapshot : "");

            log.debug("浏览器导航完成: url={}, title={}, sessionId={}", url, title, sessionId);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器导航失败: url={}, sessionId={}, error={}", url, sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器导航失败: " + e.getMessage());
        }
    }
}
