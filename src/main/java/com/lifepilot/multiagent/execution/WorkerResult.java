package com.lifepilot.multiagent.execution;

/**
 * 单个 Worker 执行结果。
 *
 * @param workerIndex Worker 序号（从 0 开始）
 * @param task        原始任务描述
 * @param success     是否执行成功
 * @param output      输出内容（成功时为 Worker 响应，失败时为错误信息）
 * @param tokensUsed  Token 消耗量
 * @param durationMs  执行耗时（毫秒）
 * @author zsg
 * @since 2026-03-22
 */
public record WorkerResult(
        int workerIndex,
        String task,
        boolean success,
        String output,
        int tokensUsed,
        long durationMs
) {}
