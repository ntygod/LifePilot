package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * 提醒候选。
 *
 * <p>表示检测器和评分模型完成后的候选结果，
 * 还未最终决定是否真正发送提醒。</p>
 *
 * @param topicKey           主题键
 * @param title              主题标题
 * @param type               候选类型
 * @param signalId           来源信号 ID
 * @param evidenceScore      证据分
 * @param timingScore        时机分
 * @param urgencyScore       紧急度
 * @param userFitScore       用户适配分
 * @param actionabilityScore 可执行性分
 * @param duplicatePenalty   重复惩罚
 * @param fatiguePenalty     疲劳惩罚
 * @param finalScore         最终分
 * @param suggestedAt        建议提醒时间
 * @param rationale          候选理由
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderCandidate(
        String topicKey,
        String title,
        ReminderCandidateType type,
        String signalId,
        float evidenceScore,
        float timingScore,
        float urgencyScore,
        float userFitScore,
        float actionabilityScore,
        float duplicatePenalty,
        float fatiguePenalty,
        float finalScore,
        @Nullable Instant suggestedAt,
        String rationale
) {

    public ReminderCandidate {
        topicKey = Objects.requireNonNull(topicKey, "topicKey 不能为空");
        title = Objects.requireNonNull(title, "title 不能为空");
        type = Objects.requireNonNull(type, "type 不能为空");
        signalId = Objects.requireNonNull(signalId, "signalId 不能为空");
        rationale = Objects.requireNonNullElse(rationale, "");
    }
}
