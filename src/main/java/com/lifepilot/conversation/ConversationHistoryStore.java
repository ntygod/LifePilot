package com.lifepilot.conversation;

import org.springframework.lang.Nullable;

/**
 * 对话历史存储抽象（与记忆系统物理解耦）。
 *
 * <p>用于 Web UI 的对话历史“保存/加载”链路，不承载 L1-L4 记忆系统的落盘。</p>
 */
public interface ConversationHistoryStore {

    /**
     * 追加一轮对话（user + assistant）到历史存储。
     *
     * @param sessionId 会话 ID（对应 chat_sessions.id）
     * @param userMessage 用户输入
     * @param assistantMessage AI 输出
     * @param reasoningSummary 本轮推理概要（可选）
     * @param traceId 关联 traceId（可选）
     */
    void appendTurn(String sessionId,
                    @Nullable String userMessage,
                    @Nullable String assistantMessage,
                    @Nullable String reasoningSummary,
                    @Nullable String traceId);

    /**
     * 追加一条系统消息到历史存储。
     *
     * <p>默认 no-op，避免破坏旧实现；需要落库的实现可覆盖。</p>
     *
     * @param sessionId 会话 ID
     * @param systemMessage 系统消息内容
     * @param traceId 关联 traceId（可选）
     */
    default void appendSystemMessage(String sessionId,
                                    @Nullable String systemMessage,
                                    @Nullable String traceId) {
        // no-op
    }
}

