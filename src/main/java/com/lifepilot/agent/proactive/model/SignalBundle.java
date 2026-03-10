package com.lifepilot.agent.proactive.model;

import lombok.Builder;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 信号包 — SignalCollector 的输出，不可变数据载体。
 *
 * <p>包含时间信号和所有 {@link com.lifepilot.agent.proactive.signal.SignalSource} 贡献的泛化信号列表。
 * {@code signals} 在构造时通过 {@link List#copyOf(java.util.Collection)} 确保不可变。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record SignalBundle(
        // 时间信号
        LocalDateTime currentTime,
        DayOfWeek dayOfWeek,
        Duration timeSinceLastInteraction,

        // 行为信号：最近 24 小时对话数量
        int recentConversationCount,

        // 泛化信号列表
        List<Signal> signals
) {

    /** 紧凑构造函数 — 确保 signals 不可变。 */
    public SignalBundle {
        signals = signals != null ? List.copyOf(signals) : List.of();
    }
}
