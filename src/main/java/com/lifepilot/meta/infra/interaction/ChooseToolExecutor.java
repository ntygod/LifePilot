package com.lifepilot.meta.infra.interaction;

import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 选择工具执行器 — 向用户推送选项列表，阻塞等待用户选择。
 *
 * @author zsg
 * @since 2026-03-08
 */
public class ChooseToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChooseToolExecutor.class);

    private final InteractionBridge interactionBridge;

    public ChooseToolExecutor(InteractionBridge interactionBridge) {
        this.interactionBridge = interactionBridge;
    }

    /**
     * 执行选择交互。
     *
     * @param input 工具输入，必需参数 message、options、sessionId
     * @return 用户选择结果
     */
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInput input) {
        try {
            String message = input.getParam("message", String.class);
            String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class)
                    .or(() -> input.getOptionalParam("sessionId", String.class))
                    .orElseThrow(() -> new IllegalArgumentException("缺少会话上下文 sessionId"));
            String streamId = input.getContextValue(ToolContextKeys.STREAM_ID, String.class).orElse(null);
            List<String> options = input.getParam("options", List.class);

            if (options == null || options.isEmpty()) {
                return ToolResult.error("选项列表不能为空");
            }

            var request = new InteractionRequest(null, InteractionType.CHOOSE, sessionId, streamId, message, options);
            var response = interactionBridge.request(request);

            if (response.timedOut()) {
                return ToolResult.error("用户响应超时");
            }

            return ToolResult.success(Map.of(
                    "selected", response.value() != null ? response.value() : "",
                    "interactionId", response.interactionId()
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("选择工具执行失败", e);
            return ToolResult.error("选择工具执行失败: " + e.getMessage());
        }
    }
}
