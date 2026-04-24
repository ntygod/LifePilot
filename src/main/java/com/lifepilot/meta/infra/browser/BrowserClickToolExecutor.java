package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 浏览器点击工具 — 使用 Playwright 点击指定元素。
 *
 * <p>支持两种定位方式（二选一）：</p>
 * <ul>
 *   <li>{@code index}：基于 {@code browser.snapshot} 注入的 {@code data-zhiwei-idx}，抗 layout 抖动</li>
 *   <li>{@code selector}：标准 CSS 选择器</li>
 * </ul>
 *
 * <p>RiskLevel MEDIUM。当 {@link BrowserSessionManager} 不可用时返回优雅降级提示。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class BrowserClickToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserClickToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserClickToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 点击指定元素，支持 index 或 selector（二选一）。
     *
     * @param input 工具输入，index/selector 二选一，可选 sessionId
     * @return 包含操作结果的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        var maybeIndex = input.getOptionalParam("index", Integer.class);
        var maybeSelector = input.getOptionalParam("selector", String.class);

        if (maybeIndex.isPresent() && maybeSelector.isPresent()) {
            return ToolResult.error("index 和 selector 只能二选一");
        }
        if (maybeIndex.isEmpty() && maybeSelector.isEmpty()) {
            return ToolResult.error("缺少必需参数: index 或 selector");
        }

        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            if (maybeIndex.isPresent()) {
                int idx = maybeIndex.get();
                page.clickByIndex(idx);
                log.debug("浏览器点击完成(index): index={}, sessionId={}", idx, sessionId);
                return ToolResult.success(Map.of(
                        "clicked", "index=" + idx,
                        "url", page.url(),
                        "title", page.title()
                ));
            } else {
                String selector = maybeSelector.get();
                page.click(selector);
                log.debug("浏览器点击完成(selector): selector={}, sessionId={}", selector, sessionId);
                return ToolResult.success(Map.of(
                        "clicked", selector,
                        "url", page.url(),
                        "title", page.title()
                ));
            }
        } catch (Exception e) {
            log.error("浏览器点击失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器点击失败: " + e.getMessage());
        }
    }
}
