package com.lifepilot.tool.search.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.function.Function;

/**
 * Layer A · 工具 schema 序列化缓存。
 *
 * <p>Key：工具 ID。Value：预序列化的 schema JSON 字符串。
 * 仅在 {@code ToolRegistryEvent.UPDATE/REMOVE} 时由索引维护器精确失效。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class SchemaCache {

    private final Cache<String, String> delegate;

    public SchemaCache(int maxSize) {
        this.delegate = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .build();
    }

    public String get(String toolId, Function<String, String> loader) {
        return delegate.get(toolId, loader);
    }

    public void invalidate(String toolId) {
        delegate.invalidate(toolId);
    }

    public void invalidateAll() {
        delegate.invalidateAll();
    }
}
