package com.lifepilot.workflow.model;

import java.time.Instant;
import java.util.Set;

import org.springframework.lang.Nullable;

import lombok.Builder;

/**
 * 工作流实例 record。
 *
 * <p>{@link WorkflowDefinition} 的一次运行时执行，维护独立的状态、
 * 变量上下文和 DAG 执行进度。每次状态转换通过 {@code toBuilder()} 生成新实例，
 * 保证不可变性。
 *
 * @param id                     实例唯一标识（UUID）
 * @param workflowId             关联的工作流定义 ID
 * @param state                  当前实例状态
 * @param context                工作流变量上下文（存储输入参数、步骤输出和中间变量）
 * @param completedStepIds       已完成步骤 ID 集合（DAG 执行进度追踪）
 * @param pendingApprovalStepId  当前等待审批的步骤 ID（PAUSED 状态时非空）
 * @param wakeUpAt               预期唤醒时间（WAITING 状态）或审批超时时间（PAUSED 状态）
 * @param blockedStepId          导致实例阻塞的步骤 ID
 * @param blockedReason          阻塞原因描述（如 "wait:60s" 或 "approval:timeout=86400s"）
 * @param startedAt              实例开始执行时间（CREATED→RUNNING 时设置）
 * @param completedAt            实例完成时间（终态时设置）
 * @param failureReason          失败原因（FAILED 状态时设置）
 * @param createdAt              实例创建时间
 * @param updatedAt              实例最后更新时间
 * @author zsg
 * @since 2026-02-26
 */
@Builder(toBuilder = true)
public record WorkflowInstance(
        String id,
        String workflowId,
        WorkflowState state,
        WorkflowContext context,
        Set<String> completedStepIds,
        @Nullable String pendingApprovalStepId,
        @Nullable Instant wakeUpAt,
        @Nullable String blockedStepId,
        @Nullable String blockedReason,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt,
        @Nullable String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
