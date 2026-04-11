package com.lifepilot.memory.episodic;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 跨会话回忆工具返回的对话片段。
 *
 * @param id               片段唯一标识
 * @param sessionId        来源会话 ID
 * @param sessionTitle     来源会话标题
 * @param sessionSummary   来源会话摘要
 * @param matchedMessageId 命中的消息 ID
 * @param hitRank          命中排名
 * @param startedAt        片段起始时间
 * @param endedAt          片段结束时间
 * @param messages         片段内的完整消息列表
 * @author zsg
 * @since 2026-03-20
 */
public record ConversationSnippetRecord(
        String id,
        String sessionId,
        @Nullable String sessionTitle,
        @Nullable String sessionSummary,
        String matchedMessageId,
        int hitRank,
        Instant startedAt,
        Instant endedAt,
        List<MessageRecord> messages
) {

    public ConversationSnippetRecord {
        messages = List.copyOf(messages);
    }
}
