package com.lifepilot.eval.feedback;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 评估反馈 — 人工对评估结果的标注。
 *
 * @param feedbackId   反馈 ID
 * @param evalId       评估结果 ID
 * @param scenarioId   场景 ID
 * @param feedbackType 反馈类型
 * @param comment      评论
 * @param goldenAnswer 标注答案
 * @param createdBy    创建者
 * @param createdAt    创建时间
 * @author zsg
 * @since 2026-03-22
 */
public record EvalFeedback(
        String feedbackId,
        String evalId,
        String scenarioId,
        FeedbackType feedbackType,
        @Nullable String comment,
        @Nullable String goldenAnswer,
        @Nullable String createdBy,
        Instant createdAt
) {
    public enum FeedbackType {
        AGREE, DISAGREE, GOLDEN_ANSWER
    }
}
