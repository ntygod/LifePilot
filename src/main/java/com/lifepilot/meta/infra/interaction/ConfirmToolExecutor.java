package com.lifepilot.meta.infra.interaction;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 确认工具执行器 — 向用户推送确认请求，阻塞等待 yes/no 响应。
 *
 * @author zsg
 * @since 2026-03-08
 */
public class ConfirmToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConfirmToolExecutor.class);

    private final InteractionBridge interactionBridge;

    public ConfirmToolExecutor(InteractionBridge interactionBridge) {
        this.interactionBridge = interactionBridge;
    }

    /**
     * 执行确认交互。
     *
     * @param input 工具输入，必需参数 message、sessionId
     * @return 确认结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String message = input.getParam("message", String.class);
            String sessionId = input.getParam("sessionId", String.class);

            var request = new InteractionRequest(null, InteractionType.CONFIRM, sessionId, message, null);
            var response = interactionBridge.request(request);

            if (response.timedOut()) {
                return ToolResult.error("用户响应超时");
            }

            return ToolResult.success(Map.of(
                    "confirmed", response.confirmed(),
                    "interactionId", response.interactionId()
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("确认工具执行失败", e);
            return ToolResult.error("确认工具执行失败: " + e.getMessage());
        }
    }
}
