package com.lifepilot.meta.infra.interaction;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 通知工具执行器 — 向用户推送通知消息，非阻塞（不等待响应）。
 *
 * @author zsg
 * @since 2026-03-08
 */
public class NotifyToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(NotifyToolExecutor.class);

    private final InteractionBridge interactionBridge;

    public NotifyToolExecutor(InteractionBridge interactionBridge) {
        this.interactionBridge = interactionBridge;
    }

    /**
     * 执行通知推送（非阻塞）。
     *
     * @param input 工具输入，必需参数 message、sessionId
     * @return 推送结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String message = input.getParam("message", String.class);
            String sessionId = input.getParam("sessionId", String.class);

            var request = new InteractionRequest(null, InteractionType.NOTIFY, sessionId, message, null);
            interactionBridge.notify(request);

            return ToolResult.success(Map.of("notified", true));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("通知工具执行失败", e);
            return ToolResult.error("通知工具执行失败: " + e.getMessage());
        }
    }
}
