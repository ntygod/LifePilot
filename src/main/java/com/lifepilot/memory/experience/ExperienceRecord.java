package com.lifepilot.memory.experience;

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
        boolean success
) {}
