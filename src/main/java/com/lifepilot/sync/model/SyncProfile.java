package com.lifepilot.sync.model;

import lombok.Builder;

/**
 * 同步配置 record，描述一个外部数据源的连接参数、同步方向、冲突策略和调度频率。
 *
 * @param id                   唯一标识
 * @param name                 配置名称
 * @param connectorType        连接器类型（"caldav" / "todoist" / "dida" / "obsidian"）
 * @param connectionParamsJson JSON 格式的连接参数
 * @param syncDirection        同步方向
 * @param conflictPolicy       冲突解决策略
 * @param cronExpression       Cron 调度表达式
 * @param enabled              是否启用
 * @param dataTypeFilterJson   JSON 数组，指定同步的数据类型（如 ["TodoItem", "ScheduleItem"]）
 * @param createdAt            创建时间（ISO 8601）
 * @param updatedAt            更新时间（ISO 8601）
 * @author zsg
 * @since 2026-02-26
 */
@Builder(toBuilder = true)
public record SyncProfile(
        String id,
        String name,
        String connectorType,
        String connectionParamsJson,
        SyncDirection syncDirection,
        ConflictPolicy conflictPolicy,
        String cronExpression,
        boolean enabled,
        String dataTypeFilterJson,
        String createdAt,
        String updatedAt
) {
}
