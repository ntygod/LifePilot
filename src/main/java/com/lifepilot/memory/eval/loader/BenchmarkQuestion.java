package com.lifepilot.memory.eval.loader;

import java.util.List;

/**
 * 针对一段对话历史的评估题。
 *
 * @param questionId         数据集稳定的题目 ID
 * @param query              问题文本
 * @param groundTruth        标准答案文本
 * @param questionType       题目类型，驱动默认 Judge 选择
 * @param evidenceSessionIds 证据 session 列表（LoCoMo 有标注，LongMemEval 可为空）
 * @author zsg
 * @since 2026-05-09
 */
public record BenchmarkQuestion(
        String questionId,
        String query,
        String groundTruth,
        QuestionType questionType,
        List<String> evidenceSessionIds
) {
    public BenchmarkQuestion {
        if (questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("questionId 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (groundTruth == null) {
            groundTruth = "";
        }
        if (questionType == null) {
            questionType = QuestionType.OPEN_ENDED;
        }
        evidenceSessionIds = evidenceSessionIds == null ? List.of() : List.copyOf(evidenceSessionIds);
    }

    /**
     * 题目类型枚举，用于 {@code BenchmarkRunner} 选择默认 Judge 策略。
     */
    public enum QuestionType {
        /** 单跳事实问答 → ExactMatchJudge。 */
        SINGLE_HOP,
        /** 多跳推理 → F1Judge + 可选 LlmAsJudge。 */
        MULTI_HOP,
        /** 开放问答 → LlmAsJudge，LLM 不可用时降级 F1Judge。 */
        OPEN_ENDED,
        /** 时序推理 → F1Judge + 可选 LlmAsJudge。 */
        TEMPORAL,
        /** 知识更新（新覆盖旧）→ ExactMatchJudge。 */
        KNOWLEDGE_UPDATE,
        /** 拒答题（应识别无法从对话回答）→ ExactMatchJudge。 */
        ABSTENTION
    }
}
