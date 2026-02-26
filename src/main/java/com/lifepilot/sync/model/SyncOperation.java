package com.lifepilot.sync.model;

/**
 * 同步操作 sealed interface，描述推送到远程的三种操作类型。
 *
 * @author zsg
 * @since 2026-02-26
 */
public sealed interface SyncOperation {

    /**
     * 创建操作：将本地新增实体推送到远程。
     *
     * @param localEntityType 本地实体类型（如 "TodoItem"、"ScheduleItem"、"HabitItem"）
     * @param localEntityId   本地实体 ID
     * @param remotePayload   远程服务所需的数据载荷
     */
    record Create(String localEntityType, String localEntityId, Object remotePayload) implements SyncOperation {
    }

    /**
     * 更新操作：将本地修改的实体同步到远程。
     *
     * @param localEntityType 本地实体类型
     * @param localEntityId   本地实体 ID
     * @param remoteEntityId  远程实体 ID
     * @param remotePayload   远程服务所需的数据载荷
     */
    record Update(String localEntityType, String localEntityId, String remoteEntityId,
                  Object remotePayload) implements SyncOperation {
    }

    /**
     * 删除操作：将本地已删除的实体从远程移除。
     *
     * @param localEntityType 本地实体类型
     * @param localEntityId   本地实体 ID
     * @param remoteEntityId  远程实体 ID
     */
    record Delete(String localEntityType, String localEntityId, String remoteEntityId) implements SyncOperation {
    }
}
