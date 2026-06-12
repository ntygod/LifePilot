package com.lifepilot.agent.learning.experience;

import java.util.List;

/**
 * 经验记录 — LLM 结构化输出的经验数据模型。
 *
 * @author zsg
 * @since 2026-03-18
 */
public record ExperienceRecord(
        String scenario,
        String strategy,
        List<String> lessons,
        List<String> applicableConditions,
        List<String> toolsUsed,
        boolean success,
        /** 失败归因：strategy（策略问题）或 system（系统问题），成功时为 null。 */
        @org.springframework.lang.Nullable String failureAttribution,
        /** 累计效果评分。 */
        float effectivenessScore,
        /** 被注入到上下文的总次数。 */
        int injectionCount,
        /** 注入后任务成功次数。 */
        int positiveOutcomes,
        /** 注入后任务失败次数。 */
        int negativeOutcomes
) {}
