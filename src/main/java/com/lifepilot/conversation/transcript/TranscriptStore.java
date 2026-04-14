package com.lifepilot.conversation.transcript;

import com.lifepilot.agent.model.CompletionMode;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Transcript 统一写入接口。
 *
 * <p>负责将 user / assistant / system / tool 等事实写入
 * {@code session_store + session_transcript_entries}。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public interface TranscriptStore {

    String appendUserMessage(String sessionId,
                             @Nullable String turnId,
                             String content,
                             @Nullable String traceId,
                             @Nullable Instant createdAt);

    String appendAssistantMessage(String sessionId,
                                  @Nullable String turnId,
                                  String content,
                                  @Nullable String reasoningSummary,
                                  @Nullable String traceId,
                                  @Nullable String a2uiComponentsJson,
                                  @Nullable String reactStepsJson,
                                  @Nullable CompletionMode completionMode,
                                  @Nullable String resumedFromTraceId,
                                  @Nullable Instant createdAt);

    default void appendTurn(String sessionId,
                            @Nullable String userMessage,
                            @Nullable String assistantMessage,
                            @Nullable String reasoningSummary,
                            @Nullable String traceId) {
        if (userMessage != null && !userMessage.isBlank()) {
            appendUserMessage(sessionId, null, userMessage, traceId, null);
        }
        if (assistantMessage != null && !assistantMessage.isBlank()) {
            appendAssistantMessage(sessionId, null, assistantMessage, reasoningSummary, traceId,
                    null, null, CompletionMode.NORMAL, null, null);
        }
    }

    /**
     * 追加用户消息，允许控制是否对用户可见。
     *
     * <p>用于 A2UI 信号等自动化交互场景——消息需要保留在模型上下文中，
     * 但不应在聊天界面展示给用户。</p>
     *
     * @param visibleToUser false 时消息不在聊天历史中展示
     */
    default String appendUserMessage(String sessionId,
                                     @Nullable String turnId,
                                     String content,
                                     @Nullable String traceId,
                                     boolean visibleToUser,
                                     @Nullable Instant createdAt) {
        if (visibleToUser) {
            return appendUserMessage(sessionId, turnId, content, traceId, createdAt);
        }
        return appendCustomMessage(sessionId, turnId, "user", content, traceId,
                true, false, createdAt);
    }

    default String appendUserMessage(String sessionId,
                                     String content,
                                     @Nullable String traceId,
                                     @Nullable Instant createdAt) {
        return appendUserMessage(sessionId, null, content, traceId, createdAt);
    }

    default String appendAssistantMessage(String sessionId,
                                          String content,
                                          @Nullable String reasoningSummary,
                                          @Nullable String traceId,
                                          @Nullable String a2uiComponentsJson,
                                          @Nullable String reactStepsJson,
                                          @Nullable CompletionMode completionMode,
                                          @Nullable String resumedFromTraceId,
                                          @Nullable Instant createdAt) {
        return appendAssistantMessage(sessionId, null, content, reasoningSummary, traceId,
                a2uiComponentsJson, reactStepsJson, completionMode, resumedFromTraceId, createdAt);
    }

    String appendSystemMessage(String sessionId,
                               String content,
                               @Nullable String traceId,
                               @Nullable Instant createdAt);

    String appendCustomMessage(String sessionId,
                               @Nullable String turnId,
                               String role,
                               String content,
                               @Nullable String traceId,
                               boolean visibleToModel,
                               boolean visibleToUser,
                               @Nullable Instant createdAt);

    String appendToolCall(String sessionId,
                          @Nullable String turnId,
                          @Nullable String traceId,
                          String toolId,
                          @Nullable String callId,
                          @Nullable String toolName,
                          String inputJson,
                          @Nullable Instant createdAt);

    String appendToolResult(String sessionId,
                            @Nullable String turnId,
                            @Nullable String traceId,
                            String toolId,
                            @Nullable String callId,
                            boolean success,
                            String outputJson,
                            @Nullable String artifactId,
                            boolean visibleToModel,
                            boolean visibleToUser,
                            @Nullable Instant createdAt);
}
