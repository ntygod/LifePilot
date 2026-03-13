package com.lifepilot.workflow.model;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * 步骤执行日志 record。
 *
 * <p>记录工作流实例中每个步骤的执行详情，包括重试尝试。
 * 每次步骤执行（含重试）都会生成一条 {@code StepLog} 记录，
 * 持久化到 {@code workflow_step_logs} 表中。
 *
 * @param id            日志唯一标识（UUID）
 * @param instanceId    关联的工作流实例 ID
 * @param stepId        步骤 ID（对应 {@link WorkflowStep#id()}）
 * @param stepType      步骤类型（如 skill、tool、llm、condition 等）
 * @param state         步骤执行状态
 * @param attempt       当前尝试次数（从 1 开始，重试时递增）
 * @param inputJson     步骤输入参数 JSON（可选）
 * @param outputJson    步骤输出结果 JSON（可选，成功时填充）
 * @param errorMessage  错误信息（可选，失败时填充）
 * @param startedAt     步骤开始执行时间（可选）
 * @param completedAt   步骤完成时间（可选）
 * @param durationMs    步骤执行耗时（毫秒，可选）
 * @param retryCount    重试次数（0 表示未重试）
 * @param createdAt     日志创建时间
 * @author zsg
 * @since 2026-02-26
 */
public record StepLog(
        String id,
        String instanceId,
        String stepId,
        String stepType,
        StepState state,
        int attempt,
        @Nullable String inputJson,
        @Nullable String outputJson,
        @Nullable String errorMessage,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt,
        @Nullable Long durationMs,
        int retryCount,
        Instant createdAt
) {
}
