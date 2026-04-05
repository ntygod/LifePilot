package com.lifepilot.agent.task.reminder;

import java.util.List;
import java.util.Objects;

/**
 * 提醒主题快照。
 *
 * <p>表示“采集完成后”提供给决策层的主题级输入。
 * 一个主题可以包含多个归一化信号。</p>
 *
 * @param topicKey 主题稳定键
 * @param title    主题标题
 * @param signals  归一化信号列表
 * @param state    历史状态
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderTopicSnapshot(
        String topicKey,
        String title,
        List<ReminderSignal> signals,
        ReminderTopicState state
) {

    public ReminderTopicSnapshot {
        topicKey = Objects.requireNonNull(topicKey, "topicKey 不能为空");
        title = Objects.requireNonNull(title, "title 不能为空");
        signals = signals != null ? List.copyOf(signals) : List.of();
        state = state != null ? state : ReminderTopicState.empty();
    }
}
