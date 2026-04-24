package com.lifepilot.tool.search.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCache_行为测试 {

    private final SchemaCache cache = new SchemaCache(100);

    @Test
    void 未命中_从loader加载并缓存() {
        AtomicInteger loaderCalls = new AtomicInteger();
        String value = cache.get("file.read", id -> {
            loaderCalls.incrementAndGet();
            return "{\"schema\": \"" + id + "\"}";
        });
        assertThat(value).isEqualTo("{\"schema\": \"file.read\"}");
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void 已缓存_loader不再触发() {
        AtomicInteger loaderCalls = new AtomicInteger();
        cache.get("file.read", id -> { loaderCalls.incrementAndGet(); return "v1"; });
        cache.get("file.read", id -> { loaderCalls.incrementAndGet(); return "v2"; });
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void invalidate_清除指定key() {
        cache.get("file.read", id -> "v1");
        cache.invalidate("file.read");
        AtomicInteger loaderCalls = new AtomicInteger();
        cache.get("file.read", id -> { loaderCalls.incrementAndGet(); return "v2"; });
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void invalidateAll_清空() {
        cache.get("a", id -> "v1");
        cache.get("b", id -> "v2");
        cache.invalidateAll();
        AtomicInteger calls = new AtomicInteger();
        cache.get("a", id -> { calls.incrementAndGet(); return "v3"; });
        cache.get("b", id -> { calls.incrementAndGet(); return "v4"; });
        assertThat(calls.get()).isEqualTo(2);
    }
}
