package com.lifepilot.agent.trace;

import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
import lombok.Builder;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 单步轨迹记录。
 *
 * @author zsg
 * @since 2026-07-20
 * @deprecated 请使用 {@link com.lifepilot.observability.trace.TraceStep}
 */
@Deprecated(forRemoval = true)
@Builder(toBuilder = true)
public record TraceStep(
        String traceId,
        int stepIndex,
        AgentPhase phaseBefore,
        AgentPhase phaseAfter,
        Action action,
        @Nullable String toolId,
        @Nullable String toolInput,
        @Nullable String toolOutput,
        boolean blocked,
        @Nullable String blockReason,
        int tokensUsed,
        long latencyMs,
        Instant timestamp
) {
}
