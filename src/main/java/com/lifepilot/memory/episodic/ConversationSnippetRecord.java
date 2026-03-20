package com.lifepilot.memory.episodic;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

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
