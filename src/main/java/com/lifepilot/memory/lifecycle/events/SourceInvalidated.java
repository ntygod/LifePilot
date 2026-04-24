package com.lifepilot.memory.lifecycle.events;

import com.lifepilot.memory.lifecycle.InvalidationKind;
import com.lifepilot.memory.lifecycle.SourceType;

/**
 * 记忆来源对象（document / knowledge base / session）失效事件。
 *
 * @author zsg
 * @since 2026-04-23
 */
public record SourceInvalidated(
    SourceType sourceType,
    String sourceId,
    InvalidationKind kind
) {}
