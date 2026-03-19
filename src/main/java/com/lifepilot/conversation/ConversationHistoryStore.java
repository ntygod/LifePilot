package com.lifepilot.conversation;

import org.springframework.lang.Nullable;

/**
 * 对话历史存储抽象（与记忆系统物理解耦）。
 *
 * <p>用于 Web UI 的对话历史"保存/加载"链路，不承载 L1-L4 记忆系统的落盘。</p>
 *
 * @author zsg
 * @since 2026-03-03
 */
public interface ConversationHistoryStore {

    /**
     * 同步写入用户消息到历史存储。
     *
     * <p>调用返回时消息行已持久化，返回后端生成的 messageId。</p>
     *
     * @param sessionId   会话 ID（对应 chat_sessions.id）
     * @param userMessage 用户输入
     * @param traceId     关联 traceId（可选）
     * @return 后端生成的 userMessageId
     * @since 2026-03-06
     */
    String appendUserMessage(String sessionId,
                             String userMessage,
                             @Nullable String traceId);

    /**
     * 同步写入助手消息到历史存储。
     *
     * <p>调用返回时消息行已持久化，返回后端生成的 messageId。</p>
     *
     * @param sessionId            会话 ID（对应 chat_sessions.id）
     * @param assistantMessage     AI 输出
     * @param reasoningSummary     本轮推理概要（可选）
     * @param traceId              关联 traceId（可选）
     * @param a2uiComponentsJson   A2UI 组件树 JSON（可选）
     * @param reactStepsJson       ReAct 步骤序列 JSON（可选）
     * @return 后端生成的 assistantMessageId
     * @since 2026-03-06
     */
    String appendAssistantMessage(String sessionId,
                                  String assistantMessage,
                                  @Nullable String reasoningSummary,
                                  @Nullable String traceId,
                                  @Nullable String a2uiComponentsJson,
                                  @Nullable String reactStepsJson);

    /**
     * 追加一轮对话（user + assistant）到历史存储。
     *
     * <p>默认实现委托给 {@link #appendUserMessage} 和 {@link #appendAssistantMessage}，
     * 保留向后兼容（CLI 等非 Web 渠道仍可直接调用）。</p>
     *
     * @param sessionId        会话 ID（对应 chat_sessions.id）
     * @param userMessage      用户输入
     * @param assistantMessage AI 输出
     * @param reasoningSummary 本轮推理概要（可选）
     * @param traceId          关联 traceId（可选）
     */
    default void appendTurn(String sessionId,
                            @Nullable String userMessage,
                            @Nullable String assistantMessage,
                            @Nullable String reasoningSummary,
                            @Nullable String traceId) {
        if (userMessage != null && !userMessage.isBlank()) {
            appendUserMessage(sessionId, userMessage, traceId);
        }
        if (assistantMessage != null && !assistantMessage.isBlank()) {
            appendAssistantMessage(sessionId, assistantMessage, reasoningSummary, traceId, null, null);
        }
    }

    /**
     * 追加一条系统消息到历史存储。
     *
     * <p>默认 no-op，避免破坏旧实现；需要落库的实现可覆盖。</p>
     *
     * @param sessionId     会话 ID
     * @param systemMessage 系统消息内容
     * @param traceId       关联 traceId（可选）
     */
    default void appendSystemMessage(String sessionId,
                                    @Nullable String systemMessage,
                                    @Nullable String traceId) {
        // no-op
    }
}
