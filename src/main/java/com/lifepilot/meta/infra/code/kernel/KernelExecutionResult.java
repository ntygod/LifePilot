package com.lifepilot.meta.infra.code.kernel;

import jakarta.annotation.Nullable;

/**
 * 内核代码执行结果。
 *
 * @param stdout     标准输出
 * @param stderr     标准错误
 * @param error      执行异常信息（无异常时为 null）
 * @param durationMs 执行耗时（毫秒）
 * @author zsg
 * @since 2026-03-31
 */
public record KernelExecutionResult(
        String stdout,
        String stderr,
        @Nullable String error,
        int durationMs
) {}
