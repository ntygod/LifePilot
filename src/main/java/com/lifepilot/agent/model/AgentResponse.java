package com.lifepilot.agent.model;

import com.lifepilot.interaction.web.model.A2uiComponent;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent response payload returned from the synchronous execution path.
 */
public record AgentResponse(
        String traceId,
        String sessionId,
        String content,
        int tokensUsed,
        int stepCount,
        @Nullable String terminationReason,
        @Nullable String messageId,
        @Nullable List<A2uiComponent> a2uiComponents
) {
    public AgentResponse(String traceId,
                         String sessionId,
                         String content,
                         int tokensUsed,
                         int stepCount,
                         @Nullable String terminationReason) {
        this(traceId, sessionId, content, tokensUsed, stepCount, terminationReason, null, null);
    }

    public static AgentResponse error(AgentState state, Exception exception) {
        return new AgentResponse(
                state.traceId(),
                state.sessionId(),
                "处理请求时发生错误: " + exception.getMessage(),
                state.budget().tokensUsed(),
                state.stepCount(),
                "异常终止: " + exception.getClass().getSimpleName(),
                null,
                null
        );
    }
}
