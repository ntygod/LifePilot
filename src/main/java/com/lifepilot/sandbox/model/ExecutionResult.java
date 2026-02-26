package com.lifepilot.sandbox.model;

/**
 * 代码执行结果。
 *
 * @param stdout     标准输出
 * @param stderr     标准错误
 * @param exitCode   退出码
 * @param durationMs 执行耗时（毫秒）
 * @param state      执行状态
 * @author zsg
 * @since 2026-03-01
 */
public record ExecutionResult(
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        ExecutionState state
) {}
