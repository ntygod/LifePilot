package com.lifepilot.conversation;

import java.time.Instant;

/**
 * 会话轮次视图模型。
 *
 * <p>当前统一由 {@code session_transcript_entries} 的读模型映射生成。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public record ConversationTurnView(
        String sessionId,
        String role,
        String content,
        Instant createdAt,
        /**
         * 当前条目关联的推理摘要。
         *
         * <p>通常来自 transcript 中助手消息 payload 的
         * {@code reasoningSummary} 字段。</p>
         */
        String reasoningSummary
) {
}
