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
 * 浏览器无障碍树快照工具 — 获取页面或子树的无障碍树结构。
 *
 * <p>RiskLevel LOW — 只读操作。maxDepth 从配置读取。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserAccessibilityToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserAccessibilityToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;
    private final MetaProperties.Infra.Browser browserConfig;

    public BrowserAccessibilityToolExecutor(@Nullable BrowserSessionManager sessionManager,
                                            MetaProperties properties) {
        this.sessionManager = sessionManager;
        this.browserConfig = properties.getInfra().getBrowser();
    }

    /**
     * 获取无障碍树快照。
     *
     * @param input 工具输入，可选参数 rootSelector、maxDepth
     * @return 包含无障碍树 JSON 的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        if (sessionManager == null || !sessionManager.isAvailable()) {
            String msg = sessionManager != null ? sessionManager.getUnavailableMessage()
                    : "浏览器功能未配置，请安装 Playwright";
            return ToolResult.error(msg);
        }

        var rootSelector = input.getOptionalParam("rootSelector", String.class).orElse(null);
        int maxDepth = input.getOptionalParam("maxDepth", Integer.class)
                .orElse(browserConfig.getAccessibilityMaxDepth());
        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            String tree = page.accessibilitySnapshot(rootSelector, maxDepth);

            var data = new LinkedHashMap<String, Object>();
            data.put("tree", tree);
            data.put("url", page.url());

            log.debug("浏览器无障碍树快照完成: rootSelector={}, maxDepth={}, sessionId={}",
                    rootSelector, maxDepth, sessionId);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器无障碍树快照失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器无障碍树快照失败: " + e.getMessage());
        }
    }
}
