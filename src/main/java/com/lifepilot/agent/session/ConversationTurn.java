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
        Instant timestamp,
        /**
         * 本轮对话的推理概要摘要。
         *
         * <p>由 AgentLoop 在每轮完成后生成并写入会话快照，
         * 用于前端历史消息中的「推理过程」折叠面板展示。</p>
         */
        String reasoningSummary
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public ConversationTurn {
        toolsUsed = List.copyOf(toolsUsed);
    }
}
