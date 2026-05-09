package com.lifepilot.memory.eval.judge;

import jakarta.annotation.Nullable;

/**
 * 答案判定结果。
 *
 * @param score      得分 [0, 1]，跳过时为 0
 * @param judgeName  判定器名称，用于报告溯源
 * @param skipped    是否被跳过（LLM-as-judge 未启用时的 abstention）
 * @param rationale  判定理由（仅 LlmAsJudge 返回时填充）
 * @author zsg
 * @since 2026-05-09
 */
public record JudgeVerdict(
        float score,
        String judgeName,
        boolean skipped,
        @Nullable String rationale
) {
    public JudgeVerdict {
        if (judgeName == null || judgeName.isBlank()) {
            throw new IllegalArgumentException("judgeName 不能为空");
        }
        if (score < 0f || score > 1f) {
            throw new IllegalArgumentException("score 必须在 [0, 1]: " + score);
        }
    }

    /** 快捷构造：完全正确。 */
    public static JudgeVerdict correct(String judgeName) {
        return new JudgeVerdict(1.0f, judgeName, false, null);
    }

    /** 快捷构造：完全错误。 */
    public static JudgeVerdict incorrect(String judgeName) {
        return new JudgeVerdict(0.0f, judgeName, false, null);
    }

    /** 快捷构造：部分得分。 */
    public static JudgeVerdict partial(String judgeName, float score) {
        return new JudgeVerdict(score, judgeName, false, null);
    }

    /** 快捷构造：跳过。 */
    public static JudgeVerdict skipped(String judgeName, String reason) {
        return new JudgeVerdict(0.0f, judgeName, true, reason);
    }
}
