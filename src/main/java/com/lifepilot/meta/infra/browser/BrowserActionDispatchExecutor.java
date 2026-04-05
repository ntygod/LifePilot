package com.lifepilot.meta.infra.browser;

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
 * <p>统一承接 navigate / interact / screenshot / evaluate / accessibility / tab / close。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class BrowserActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserActionDispatchExecutor.class);

    @Nullable
    private final BrowserSessionManager browserSessionManager;

    public BrowserActionDispatchExecutor(BrowserNavigateToolExecutor navigateExecutor,
                                         BrowserClickToolExecutor clickExecutor,
                                         BrowserInputToolExecutor inputExecutor,
                                         BrowserScreenshotToolExecutor screenshotExecutor,
                                         BrowserScrollToolExecutor scrollExecutor,
                                         BrowserWaitToolExecutor waitExecutor,
                                         BrowserHoverToolExecutor hoverExecutor,
                                         BrowserSelectToolExecutor selectExecutor,
                                         BrowserKeyboardToolExecutor keyboardExecutor,
                                         BrowserEvaluateToolExecutor evaluateExecutor,
                                         BrowserAccessibilityToolExecutor accessibilityExecutor,
                                         BrowserTabToolExecutor tabExecutor,
                                         @Nullable BrowserSessionManager browserSessionManager) {
        this.browserSessionManager = browserSessionManager;
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
        register("close",
                RiskLevel.LOW,
                browserSessionSemantics,
                input -> {
                    try {
                        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");
                        if (browserSessionManager == null) {
                            return ToolResult.error("浏览器会话管理器不可用");
                        }
                        browserSessionManager.closePage(sessionId);
                        return ToolResult.success(Map.of("message", "浏览器会话已关闭: " + sessionId));
                    } catch (Exception e) {
                        return ToolResult.error("关闭浏览器失败: " + e.getMessage());
                    }
                });
    }

    @Override
    public ToolResult execute(ToolInput input) {
        // 在分派前注册会话级模式覆盖（仅首次创建会话时生效）
        applySessionModeOverride(input);
        return super.execute(input);
    }

    /**
     * 从工具输入中提取模式覆盖参数，注册到 BrowserSessionManager。
     * 仅在用户显式传入 acquisitionMode 时才覆盖。
     */
    private void applySessionModeOverride(ToolInput input) {
        if (browserSessionManager == null) {
            return;
        }
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
