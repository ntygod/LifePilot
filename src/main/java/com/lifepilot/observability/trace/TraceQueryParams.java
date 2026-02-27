package com.lifepilot.observability.trace;

import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;

/**
 * 轨迹查询参数 — 支持多维度过滤和分页。
 *
 * @param startTime   起始时间（可为 null）
 * @param endTime     结束时间（可为 null）
 * @param sessionId   会话 ID 过滤（可为 null）
 * @param successOnly 仅查询成功的 Trace（可为 null 表示不过滤）
 * @param minSteps    最小步骤数
 * @param minTokens   最小 Token 数
 * @param limit       分页大小
 * @param offset      分页偏移
 * @author zsg
 * @since 2026-02-27
 */
@Builder(toBuilder = true)
public record TraceQueryParams(
        @Nullable Instant startTime,
        @Nullable Instant endTime,
        @Nullable String sessionId,
        @Nullable Boolean successOnly,
        int minSteps,
        int minTokens,
        int limit,
        int offset
) {
}
