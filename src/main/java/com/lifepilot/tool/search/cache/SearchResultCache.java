package com.lifepilot.tool.search.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lifepilot.tool.search.ToolSearchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Layer B · 跨会话搜索结果缓存。
 *
 * <p>Key：sha256(query + category + limit) 归一化字符串。
 * TTL：默认 5 分钟；{@code ToolRegistryEvent} 触发全表清（粗粒度）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class SearchResultCache {

    private final Cache<String, ToolSearchResult> delegate;

    public SearchResultCache(int maxSize, Duration ttl) {
        this.delegate = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }

    public Optional<ToolSearchResult> get(String query, String category, int limit) {
        return Optional.ofNullable(delegate.getIfPresent(key(query, category, limit)));
    }

    public void put(String query, String category, int limit, ToolSearchResult result) {
        delegate.put(key(query, category, limit), result);
    }

    public void invalidateAll() {
        delegate.invalidateAll();
    }

    private String key(String query, String category, int limit) {
        String raw = Objects.toString(query, "") + "|"
                + Objects.toString(category, "") + "|"
                + limit;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SearchResultCache key 生成失败", e);
        }
    }
}
