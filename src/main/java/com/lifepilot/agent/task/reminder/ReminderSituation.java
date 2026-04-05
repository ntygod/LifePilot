package com.lifepilot.agent.task.reminder;

import java.util.List;

/**
 * 情境合成结果 — 多个相关主题合并后的统一提醒。
 *
 * @param topicKeys 合并的主题 key 列表
 * @param message   合成后的情境化文案
 * @param priority  优先级（high / medium / low）
 * @param decisions 原始决策列表
 * @author zsg
 * @since 2026-04-05
 */
public record ReminderSituation(
        List<String> topicKeys,
        String message,
        String priority,
        List<ReminderDecisionOutcome> decisions
) {
    public ReminderSituation {
        topicKeys = topicKeys != null ? List.copyOf(topicKeys) : List.of();
        message = message != null ? message : "";
        priority = priority != null ? priority : "medium";
        decisions = decisions != null ? List.copyOf(decisions) : List.of();
    }
}
