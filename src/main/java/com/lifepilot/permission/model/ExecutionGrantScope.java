package com.lifepilot.permission.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 授权作用域。
 *
 * <p>使用结构化键值描述授权边界，例如路径、域名、仓库或集合名。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public record ExecutionGrantScope(Map<String, Object> values) {

    public static final ExecutionGrantScope EMPTY = new ExecutionGrantScope(Map.of());

    public ExecutionGrantScope {
        values = values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Nullable
    public Object get(String key) {
        return values.get(key);
    }

    public static ExecutionGrantScope of(@Nullable Map<String, Object> values) {
        return values == null || values.isEmpty() ? EMPTY : new ExecutionGrantScope(values);
    }
}
