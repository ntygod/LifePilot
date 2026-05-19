package com.lifepilot.agent.initiative.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 信号 — 外部事件的结构化表示。信号源是纯粹的事件收集器，不做决策。
 *
 * @author zsg
 * @since 2026-06-01
 */
public sealed interface Signal permits
        Signal.ConversationEnded,
        Signal.TimeElapsed,
        Signal.MemoryChanged,
        Signal.UserReturned,
        Signal.IdleDetected {

    Instant timestamp();

    /** 一轮对话结束。 */
    record ConversationEnded(String sessionId, String summary, Instant timestamp) implements Signal {}

    /** 时间流逝（用于到期类提醒）。 */
    record TimeElapsed(Instant timestamp, List<String> upcomingDeadlines) implements Signal {}

    /** 记忆发生变化（新实体写入、实体过期等）。 */
    record MemoryChanged(String entityId, String changeType, Instant timestamp) implements Signal {}

    /** 用户回来了（从不活跃变为活跃）。 */
    record UserReturned(String channel, Instant timestamp) implements Signal {}

    /** 系统检测到空闲（无对话超过阈值时间）。 */
    record IdleDetected(Duration idleDuration, Instant timestamp) implements Signal {}
}
