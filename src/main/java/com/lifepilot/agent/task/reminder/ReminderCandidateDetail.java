package com.lifepilot.agent.task.reminder;

/**
 * ReminderBehavior 专属候选详情 — 存储在 ProactiveCandidate.detail 中，
 * 由 detect() 阶段产出，reason() 阶段取回使用。
 *
 * @param snapshot     主题快照（reason 阶段需要用于消息生成）
 * @param candidate    原始提醒候选（保留完整评分细节）
 * @param policyConfig 策略配置（reason 阶段需要用于学习策略）
 * @author zsg
 * @since 2026-04-14
 */
public record ReminderCandidateDetail(
        ReminderTopicSnapshot snapshot,
        ReminderCandidate candidate,
        ReminderPolicyConfig policyConfig
) {}
