package com.lifepilot.memory.governance.lifecycle.events;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.store.entity.EntityType;

import java.util.Objects;

/**
 * 实体生命周期状态变化事件（Spring ApplicationEvent）。
 * oldState 可能为 null（新建实体时）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public record EntityLifecycleChanged(
    String entityId,
    String entityType,
    LifecycleState oldState,
    LifecycleState newState,
    String reason,
    ChangeSource source
) {
    public EntityLifecycleChanged {
        entityId = requireCleanText(entityId, "生命周期变化事件 entityId");
        entityType = requireCleanText(entityType, "生命周期变化事件 entityType");
        try {
            EntityType.valueOf(entityType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("生命周期变化事件 entityType 未知: " + entityType, e);
        }
        Objects.requireNonNull(newState, "生命周期变化事件 newState 不能为空");
        Objects.requireNonNull(source, "生命周期变化事件 source 不能为空");
    }

    private static String requireCleanText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " 不能包含首尾空白: " + value);
        }
        return value;
    }
}
