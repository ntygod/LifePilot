package com.lifepilot.agent.model;

import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent 同步执行路径返回的响应载荷。
 *
 * @author zsg
 * @since 2026-03-25
 */
public record AgentResponse(
        String traceId,
        String sessionId,
        @Nullable String turnId,
        AgentTaskMode taskMode,
        String content,
        int tokensUsed,
        int stepCount,
        @Nullable String terminationReason,
        @Nullable CompletionReason completionReason,
        @Nullable String assistantEntryId,
        @Nullable List<A2uiComponent> a2uiComponents,
        @Nullable TokenUsage tokenUsage,
        CompletionMode completionMode,
        @Nullable String resumedFromTraceId,
        ChatTurnStatus turnStatus
) {
    public AgentResponse {
        taskMode = taskMode != null ? taskMode : AgentTaskMode.AUTO;
        completionMode = completionMode != null ? completionMode : CompletionMode.NORMAL;
    }

    public AgentResponse(String traceId,
                         String sessionId,
                         String content,
                         int tokensUsed,
                         int stepCount,
                         @Nullable String terminationReason) {
        this(traceId, sessionId, null, AgentTaskMode.AUTO, content, tokensUsed, stepCount, terminationReason,
                null, null, null, null, CompletionMode.NORMAL, null,
                terminationReason != null && !terminationReason.isBlank()
                        ? ChatTurnStatus.FAILED
                        : ChatTurnStatus.SUCCESS);
    }

    public AgentResponse(String traceId,
                         String sessionId,
                         String content,
                         int tokensUsed,
                         int stepCount,
                         @Nullable String terminationReason,
                         @Nullable String assistantEntryId,
                         @Nullable List<A2uiComponent> a2uiComponents,
                         @Nullable TokenUsage tokenUsage,
                         @Nullable CompletionMode completionMode,
                         @Nullable String resumedFromTraceId) {
        this(traceId, sessionId, null, AgentTaskMode.AUTO, content, tokensUsed, stepCount, terminationReason,
                null, assistantEntryId, a2uiComponents, tokenUsage,
                completionMode != null ? completionMode : CompletionMode.NORMAL,
                resumedFromTraceId,
                completionMode == CompletionMode.DEGRADED
                        ? ChatTurnStatus.DEGRADED
                        : (terminationReason != null && !terminationReason.isBlank()
                        ? ChatTurnStatus.FAILED
                        : ChatTurnStatus.SUCCESS));
    }

    public AgentResponse(String traceId,
                         String sessionId,
                         @Nullable String turnId,
                         String content,
                         int tokensUsed,
                         int stepCount,
                         @Nullable String terminationReason) {
        this(traceId, sessionId, turnId, AgentTaskMode.AUTO, content, tokensUsed, stepCount, terminationReason,
                null, null, null, null, CompletionMode.NORMAL, null, ChatTurnStatus.FAILED);
    }

    /**
     * 从 ReactAgentState 构建错误响应。
     */
    public static AgentResponse error(ReactAgentState state, Exception exception) {
        return new AgentResponse(
                state.traceId(),
                state.sessionId(),
                state.turnId(),
                state.taskMode(),
                "处理请求时发生错误: " + exception.getMessage(),
                state.budget().tokensUsed(),
                state.stepCount(),
                "异常终止: " + exception.getClass().getSimpleName(),
                CompletionReason.UNEXPECTED_EXCEPTION,
                null,
                null,
                null,
                CompletionMode.NORMAL,
                state.resumedFromTraceId(),
                ChatTurnStatus.FAILED
        );
    }
}
