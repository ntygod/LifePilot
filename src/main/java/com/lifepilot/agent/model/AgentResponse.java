package com.lifepilot.agent.model;

import org.springframework.lang.Nullable;

/**
 * Agent 响应 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record AgentResponse(
        String traceId,
        String sessionId,
        String content,
        int tokensUsed,
        int stepCount,
        @Nullable String terminationReason
) {
    /**
     * 错误响应工厂方法。
     *
     * @param state     当前 Agent 状态
     * @param exception 异常
     * @return 错误响应
     */
    public static AgentResponse error(AgentState state, Exception exception) {
        return new AgentResponse(
                state.traceId(),
                state.sessionId(),
                "处理请求时发生错误: " + exception.getMessage(),
                state.budget().tokensUsed(),
                state.stepCount(),
                "异常终止: " + exception.getClass().getSimpleName()
        );
    }
}
