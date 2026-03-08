package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器截图工具 — 使用 Playwright 截取页面截图并返回 Base64 编码。
 *
 * <p>RiskLevel LOW。当 {@link BrowserSessionManager} 不可用时返回优雅降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserScreenshotToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserScreenshotToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserScreenshotToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 截取页面截图。
     *
     * @param input 工具输入，可选参数 sessionId 和 fullPage
     * @return 包含 Base64 编码截图的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        String sessionId = input.getOptionalParam("sessionId", String.class)
                .orElse("default");

        boolean fullPage = input.getOptionalParam("fullPage", Boolean.class)
                .orElse(false);

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            String base64 = page.screenshot(fullPage);

            log.debug("浏览器截图完成: sessionId={}, fullPage={}, size={}",
                    sessionId, fullPage, base64.length());
            return ToolResult.success(Map.of(
                    "screenshot", base64,
                    "url", page.url(),
                    "fullPage", fullPage
            ));
        } catch (BrowserNotInstalledException e) {
            log.warn("浏览器引擎未安装: {}", e.getMessage());
            return ToolResult.error(
                    "浏览器引擎未安装。请在终端运行以下命令安装：\n" +
                    "mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"\n" +
                    "安装完成后重试即可。"
            );
        } catch (Exception e) {
            log.error("浏览器截图失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器截图失败: " + e.getMessage());
        }
    }
}
