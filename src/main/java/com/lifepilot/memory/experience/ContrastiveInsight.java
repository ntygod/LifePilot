package com.lifepilot.memory.experience;

import java.util.List;

/**
 * 对比洞察记录 — LLM 结构化输出，包含成功/失败轨迹对比分析结果。
 *
 * @author zsg
 * @since 2026-03-18
 */
public record ContrastiveInsight(
        /** 失败根因。 */
        String failureReason,
        /** 成功关键因素。 */
        String successFactor,
        /** 对比教训列表。 */
        List<String> contrastiveLessons,
        /** 规避策略。 */
        String avoidanceStrategy
) {}
