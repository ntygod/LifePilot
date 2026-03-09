package com.lifepilot.workflow.model;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * 工作流审计事件 record。
 *
 * @param id         事件唯一标识（UUID）
 * @param instanceId 工作流实例 ID
 * @param workflowId 工作流定义 ID
 * @param type       事件类型
 * @param stepId     步骤 ID（实例级事件为 null）
 * @param dataJson   事件数据 JSON（可空）
 * @param createdAt  事件时间
 * @author zsg
 * @since 2026-03-09
 */
public record WorkflowEvent(
        String id,
        String instanceId,
        String workflowId,
        WorkflowEventType type,
        @Nullable String stepId,
        @Nullable String dataJson,
        Instant createdAt
) {
}
