package com.lifepilot.agent.model;

import com.lifepilot.interaction.model.ArtifactRef;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent 同步执行路径返回的响应载荷。
 *
 * <p>{@code artifactRefs} 字段（2026-05-17 引入）承载本轮 Agent 主循环工具调用产生的
 * 文件产物引用。{@code ExecutionMiddleware} 在构造 {@code GatewayResponse} 时把它
 * 透传到协议层，最终由 {@code ChannelDeliveryDispatcher} 按渠道差异化分发。</p>
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
        ChatTurnStatus turnStatus,
        List<ArtifactRef> artifactRefs
) {
    public AgentResponse {
        taskMode = taskMode != null ? taskMode : AgentTaskMode.AUTO;
        completionMode = completionMode != null ? completionMode : CompletionMode.NORMAL;
        artifactRefs = artifactRefs != null ? List.copyOf(artifactRefs) : List.of();
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
                        : ChatTurnStatus.SUCCESS,
                List.of());
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
                        : ChatTurnStatus.SUCCESS),
                List.of());
    }

    public AgentResponse(String traceId,
                         String sessionId,
                         @Nullable String turnId,
                         String content,
                         int tokensUsed,
                         int stepCount,
                         @Nullable String terminationReason) {
        this(traceId, sessionId, turnId, AgentTaskMode.AUTO, content, tokensUsed, stepCount, terminationReason,
                null, null, null, null, CompletionMode.NORMAL, null, ChatTurnStatus.FAILED, List.of());
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
                ChatTurnStatus.FAILED,
                List.of()
        );
    }

    /** 派生新实例：替换 artifactRefs 字段；其他字段保持不变。 */
    public AgentResponse withArtifactRefs(List<ArtifactRef> refs) {
        return new AgentResponse(
                traceId, sessionId, turnId, taskMode, content, tokensUsed, stepCount,
                terminationReason, completionReason, assistantEntryId, a2uiComponents,
                tokenUsage, completionMode, resumedFromTraceId, turnStatus,
                refs != null ? List.copyOf(refs) : List.of()
        );
    }
}
