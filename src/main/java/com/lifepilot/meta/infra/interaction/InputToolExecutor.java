package com.lifepilot.meta.infra.interaction;

import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 输入工具执行器 — 向用户推送输入提示，阻塞等待用户输入。
 *
 * @author zsg
 * @since 2026-03-08
 */
public class InputToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(InputToolExecutor.class);

    private final InteractionBridge interactionBridge;

    public InputToolExecutor(InteractionBridge interactionBridge) {
        this.interactionBridge = interactionBridge;
    }

    /**
     * 执行输入交互。
     *
     * @param input 工具输入，必需参数 message、sessionId
     * @return 用户输入结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String message = input.getParam("message", String.class);
            String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class)
                    .or(() -> input.getOptionalParam("sessionId", String.class))
                    .orElseThrow(() -> new IllegalArgumentException("缺少会话上下文 sessionId"));
            String streamId = input.getContextValue(ToolContextKeys.STREAM_ID, String.class).orElse(null);

            var request = new InteractionRequest(null, InteractionType.INPUT, sessionId, streamId, message, null);
            var response = interactionBridge.request(request);

            if (response.timedOut()) {
                return ToolResult.error("用户响应超时");
            }

            return ToolResult.success(Map.of(
                    "input", response.value() != null ? response.value() : "",
                    "interactionId", response.interactionId()
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("输入工具执行失败", e);
            return ToolResult.error("输入工具执行失败: " + e.getMessage());
        }
    }
}
