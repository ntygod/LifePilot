package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;

/**
 * 状态转换步骤 — 记录 Agent 状态机的一次状态转换。
 *
 * @param stepIndex     步骤序号
 * @param timestamp     发生时间
 * @param duration      耗时
 * @param phaseBefore   转换前阶段
 * @param phaseAfter    转换后阶段
 * @param actionType    触发转换的动作类型
 * @param actionSummary 动作摘要
 * @author zsg
 * @since 2026-02-27
 */
public record StateTransitionStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String phaseBefore,
        String phaseAfter,
        String actionType,
        String actionSummary
) implements TraceStep {

    @Override
    public String typeName() {
        return "state_transition";
    }
}
