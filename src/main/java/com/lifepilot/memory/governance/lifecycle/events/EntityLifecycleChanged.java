package com.lifepilot.memory.governance.lifecycle.events;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;

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
) {}
