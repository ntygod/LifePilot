package com.lifepilot.agent.task.proactive.training;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.agent.task.reminder.ReminderAction;

import java.time.Instant;

/**
 * 主动引擎 few-shot 训练样本。
 *
 * <p>由 {@link ProactiveTrainingReplayService} 从历史反馈三元组编译生成，
 * 由 {@link ProactiveFewShotLibrary} 持久化，供未来 Gate 3 LLM prompt 拼接使用。</p>
 *
 * @param candidateType     候选类型（如 DUE_SOON / COMMITMENT 等）
 * @param topicSummary      话题简要描述（截断至 80 字符以内）
 * @param historicalAction  历史执行的动作（SKIP / SOFT_PUSH / NORMAL_PUSH / ...）
 * @param reward            历史反馈 reward（0.0-1.0）
 * @param contextDigest     上下文摘要（时段 / focus / boundary 等，简要可读）
 * @param originalDecidedAt 原始决策时间
 * @param positive          是否为正例（reward ≥ positiveThreshold）
 * @author zsg
 * @since 2026-05-09
 */
public record ProactiveFewShotSample(
        String candidateType,
        String topicSummary,
        ReminderAction historicalAction,
        float reward,
        String contextDigest,
        Instant originalDecidedAt,
        boolean positive
) {

    @JsonCreator
    public ProactiveFewShotSample(
            @JsonProperty("candidateType") String candidateType,
            @JsonProperty("topicSummary") String topicSummary,
            @JsonProperty("historicalAction") ReminderAction historicalAction,
            @JsonProperty("reward") float reward,
            @JsonProperty("contextDigest") String contextDigest,
            @JsonProperty("originalDecidedAt") Instant originalDecidedAt,
            @JsonProperty("positive") boolean positive) {
        this.candidateType = candidateType;
        this.topicSummary = topicSummary;
        this.historicalAction = historicalAction;
        this.reward = reward;
        this.contextDigest = contextDigest;
        this.originalDecidedAt = originalDecidedAt;
        this.positive = positive;
    }
}
