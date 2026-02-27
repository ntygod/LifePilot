package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;

/**
 * 回放步骤 — 包含人类可读摘要的步骤信息。
 *
 * @param index        步骤序号
 * @param typeName     步骤类型名称
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param summary      人类可读摘要
 * @param originalStep 原始步骤
 * @author zsg
 * @since 2026-02-27
 */
public record ReplayStep(
        int index,
        String typeName,
        Instant timestamp,
        Duration duration,
        String summary,
        TraceStep originalStep
) {
}
