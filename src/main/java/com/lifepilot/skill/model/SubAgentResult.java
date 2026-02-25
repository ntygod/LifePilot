package com.lifepilot.skill.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * SubAgent 执行结果 — 记录 Skill 激活后的完整执行信息。
 *
 * <p>包含执行状态、输出内容、资源消耗和追踪信息，
 * 用于 {@link com.lifepilot.skill.activation.SubAgentFactory} 返回激活结果。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
@Builder(toBuilder = true)
public record SubAgentResult(
        String skillId,
        boolean success,
        String output,
        @Nullable String terminationReason,
        int tokensUsed,
        int stepsExecuted,
        long durationMs,
        String traceId
) {}
