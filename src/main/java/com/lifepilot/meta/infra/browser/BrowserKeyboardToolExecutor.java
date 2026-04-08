package com.lifepilot.meta.infra.browser;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览器键盘工具 — 模拟按键或逐字符输入文本。
 *
 * <p>RiskLevel MEDIUM。支持单键、组合键和文本输入两种模式。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class BrowserKeyboardToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(BrowserKeyboardToolExecutor.class);

    @Nullable
    private final BrowserSessionManager sessionManager;

    public BrowserKeyboardToolExecutor(@Nullable BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 执行键盘操作。
     *
     * @param input 工具输入，可选参数 key、text、type（默认 "key"）
     * @return 包含操作结果的结构化结果
     */
    public ToolResult execute(ToolInput input) {
        var keyOpt = input.getOptionalParam("key", String.class);
        var textOpt = input.getOptionalParam("text", String.class);

        if (keyOpt.isEmpty() && textOpt.isEmpty()) {
            return ToolResult.error("参数 key 和 text 至少提供一个");
        }

        String type = input.getOptionalParam("type", String.class).orElse("key");
        String sessionId = input.getOptionalParam("sessionId", String.class).orElse("default");

        try {
            var page = sessionManager.getOrCreatePage(sessionId);
            var data = new LinkedHashMap<String, Object>();

            if ("text".equals(type) && textOpt.isPresent()) {
                page.typeText(textOpt.get());
                data.put("action", "type");
                data.put("text", textOpt.get());
            } else if (keyOpt.isPresent()) {
                page.pressKey(keyOpt.get());
                data.put("action", "press");
                data.put("key", keyOpt.get());
            } else {
                return ToolResult.error("type=key 时必须提供 key 参数，type=text 时必须提供 text 参数");
            }

            data.put("url", page.url());
            log.debug("浏览器键盘操作完成: action={}, sessionId={}", data.get("action"), sessionId);
            return ToolResult.success(Map.copyOf(data));
        } catch (Exception e) {
            log.error("浏览器键盘操作失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return ToolResult.error("浏览器键盘操作失败: " + e.getMessage());
        }
    }
}
