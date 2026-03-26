package com.lifepilot.permission.model;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public List<String> stringValues(String key) {
        return toStringList(values.get(key));
    }

    @Nullable
    public String firstValue(String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            List<String> values = stringValues(key);
            if (!values.isEmpty()) {
                return values.getFirst();
            }
        }
        return null;
    }

    public static ExecutionGrantScope of(@Nullable Map<String, Object> values) {
        return values == null || values.isEmpty() ? EMPTY : new ExecutionGrantScope(values);
    }

    public static List<String> toStringList(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (Object item : list) {
                String normalized = normalizeScalar(item);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
            return List.copyOf(result);
        }
        String normalized = normalizeScalar(value);
        return normalized != null ? List.of(normalized) : List.of();
    }

    @Nullable
    private static String normalizeScalar(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? null : text;
    }
}
