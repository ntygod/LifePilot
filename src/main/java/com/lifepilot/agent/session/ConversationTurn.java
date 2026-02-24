package com.lifepilot.agent.session;

import java.time.Instant;
import java.util.List;

/**
 * 对话轮次 record。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record ConversationTurn(
        String userMessage,
        String agentResponse,
        List<String> toolsUsed,
        Instant timestamp
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public ConversationTurn {
        toolsUsed = List.copyOf(toolsUsed);
    }
}
