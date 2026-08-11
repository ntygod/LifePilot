package com.lifepilot.memory.governance.lifecycle.events;

import com.lifepilot.memory.governance.lifecycle.InvalidationKind;
import com.lifepilot.memory.governance.lifecycle.SourceType;

import java.util.Objects;

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
) {
    public SourceInvalidated {
        Objects.requireNonNull(sourceType, "源对象类型不能为空");
        sourceId = requireCleanText(sourceId, "源对象 ID");
        Objects.requireNonNull(kind, "源失效类型不能为空");
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
