package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器工具 action 路由执行器。
 *
 * <p>统一承接所有浏览器操作：navigate / click / input / scroll / wait /
 * hover / select / keyboard / screenshot / evaluate / accessibility /
 * tab / storage / close。</p>
 *
 * <p>在分派前统一检查 Playwright 可用性和捕获 {@link BrowserNotInstalledException}，
 * 各子 executor 无需重复检查。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class BrowserActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserActionDispatchExecutor.class);

    @Nullable
    private final BrowserSessionManager browserSessionManager;

    public BrowserActionDispatchExecutor(@Nullable BrowserSessionManager browserSessionManager,
                                         MetaProperties properties,
                                         TextSnapshotCleaner textSnapshotCleaner,
                                         InteractiveElementIndexer indexer) {
        this.browserSessionManager = browserSessionManager;

        var browserConfig = properties.getInfra().getBrowser();
        int navigateTimeoutMs = (int) Math.min(
                (long) browserConfig.getToolTimeoutSeconds() * 1000, Integer.MAX_VALUE);

        // 创建子 executor
        var navigateExecutor = new BrowserNavigateToolExecutor(browserSessionManager, textSnapshotCleaner, navigateTimeoutMs);
        var clickExecutor = new BrowserClickToolExecutor(browserSessionManager);
        var inputExecutor = new BrowserInputToolExecutor(browserSessionManager);
        var screenshotExecutor = new BrowserScreenshotToolExecutor(browserSessionManager);
        var scrollExecutor = new BrowserScrollToolExecutor(browserSessionManager, properties);
        var waitExecutor = new BrowserWaitToolExecutor(browserSessionManager, properties);
        var hoverExecutor = new BrowserHoverToolExecutor(browserSessionManager);
        var selectExecutor = new BrowserSelectToolExecutor(browserSessionManager);
        var keyboardExecutor = new BrowserKeyboardToolExecutor(browserSessionManager);
        var evaluateExecutor = new BrowserEvaluateToolExecutor(browserSessionManager, properties);
        var accessibilityExecutor = new BrowserAccessibilityToolExecutor(browserSessionManager, properties);
        var tabExecutor = new BrowserTabToolExecutor(browserSessionManager);
        var storageExecutor = new BrowserStorageToolExecutor(browserSessionManager);
        var snapshotExecutor = new BrowserSnapshotToolExecutor(browserSessionManager, indexer, properties);

        // 注册 action
        ToolExecutionSemantics browserSessionSemantics = ToolExecutionSemantics.of(
                PermissionActionType.BROWSER_AUTOMATION,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.exactValues("sessionIds", "sessionId")
        );

        register("navigate",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.BROWSER_AUTOMATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.composite(
                                ToolScopeResolvers.origins("url"),
                                ToolScopeResolvers.exactValues("sessionIds", "sessionId")
                        )
                ),
                navigateExecutor::execute);

        register("click", RiskLevel.MEDIUM, browserSessionSemantics, clickExecutor::execute);
        register("input", RiskLevel.MEDIUM, browserSessionSemantics, inputExecutor::execute);
        register("scroll", RiskLevel.MEDIUM, browserSessionSemantics, scrollExecutor::execute);
        register("wait", RiskLevel.LOW, browserSessionSemantics, waitExecutor::execute);
        register("hover", RiskLevel.MEDIUM, browserSessionSemantics, hoverExecutor::execute);
        register("select", RiskLevel.MEDIUM, browserSessionSemantics, selectExecutor::execute);
        register("keyboard", RiskLevel.MEDIUM, browserSessionSemantics, keyboardExecutor::execute);
        register("screenshot", RiskLevel.LOW, browserSessionSemantics, screenshotExecutor::execute);
        register("evaluate", RiskLevel.HIGH, browserSessionSemantics, evaluateExecutor::execute);
        register("accessibility", RiskLevel.LOW, browserSessionSemantics, accessibilityExecutor::execute);
        register("tab", RiskLevel.MEDIUM, browserSessionSemantics, tabExecutor::execute);
        register("storage", RiskLevel.MEDIUM, browserSessionSemantics, storageExecutor::execute);
        register("snapshot", RiskLevel.LOW, browserSessionSemantics, snapshotExecutor::execute);
        register("close",
                RiskLevel.LOW,
                browserSessionSemantics,
                input -> {
                    if (browserSessionManager == null) {
                        return ToolResult.error("浏览器功能未配置");
                    }
                    String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
                    try {
                        browserSessionManager.closePage(sessionId);
                    } catch (Exception e) {
                        return ToolResult.error("关闭浏览器会话失败: " + e.getMessage());
                    }
                    return ToolResult.success(Map.of("message", "浏览器会话已关闭: " + sessionId));
                });
    }

    @Override
    public ToolResult execute(ToolInput input) {
        // 统一可用性检查 — 所有子 executor 无需重复
        if (browserSessionManager == null || !browserSessionManager.isAvailable()) {
            String msg = browserSessionManager != null
                    ? browserSessionManager.getUnavailableMessage()
                    : "浏览器功能未配置";
            return ToolResult.error(msg + "，改用 web.fetch 抓取静态内容");
        }

        try {
            // 在分派前注册会话级模式覆盖（仅首次创建会话时生效）
            applySessionModeOverride(input);
            return super.execute(input);
        } catch (BrowserNotInstalledException e) {
            log.warn("浏览器引擎未安装: {}", e.getMessage());
            return ToolResult.error(
                    "浏览器引擎未安装。请在终端运行以下命令安装：\n" +
                    "mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install chromium\"\n" +
                    "安装完成后重试即可。"
            );
        }
    }

    /**
     * 从工具输入中提取模式覆盖参数，注册到 BrowserSessionManager。
     * 仅在用户显式传入 acquisitionMode 时才覆盖。
     */
    private void applySessionModeOverride(ToolInput input) {
        var modeStr = input.getOptionalParam("acquisitionMode", String.class).orElse(null);
        if (modeStr == null) {
            return;
        }
        BrowserAcquisitionMode mode;
        try {
            mode = BrowserAcquisitionMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的浏览器模式: {}，忽略覆盖", modeStr);
            return;
        }
        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
        String cdpUrl = input.getOptionalParam("cdpUrl", String.class).orElse(null);
        String userDataDir = input.getOptionalParam("userDataDir", String.class).orElse(null);
        var override = new BrowserSessionManager.SessionModeOverride(mode, cdpUrl, userDataDir);
        browserSessionManager.registerSessionMode(sessionId, override);
        log.info("浏览器会话模式覆盖已注册: sessionId={}, mode={}", sessionId, mode);
    }
}
