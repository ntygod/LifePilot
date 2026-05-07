package com.lifepilot.tool.search.cache;

import com.lifepilot.tool.search.ToolSearchResult;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer C · 会话内搜索 memo。
 *
 * <p>Key：traceId + "|" + query + "|" + category + "|" + limit + "|" + scopeKey。
 * 同一轮对话内重复搜索同一 query 近零成本。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class SessionSearchMemo {

    private final Map<String, ToolSearchResult> store = new ConcurrentHashMap<>();

    public Optional<ToolSearchResult> get(String traceId, String query, String category, int limit) {
        return get(traceId, query, category, limit, "");
    }

    public Optional<ToolSearchResult> get(String traceId, String query, String category, int limit, String scopeKey) {
        return Optional.ofNullable(store.get(key(traceId, query, category, limit, scopeKey)));
    }

    public void put(String traceId, String query, String category, int limit, ToolSearchResult result) {
        put(traceId, query, category, limit, "", result);
    }

    public void put(String traceId, String query, String category, int limit, String scopeKey, ToolSearchResult result) {
        store.put(key(traceId, query, category, limit, scopeKey), result);
    }

    public void clear() {
        store.clear();
    }

    private String key(String traceId, String query, String category, int limit) {
        return key(traceId, query, category, limit, "");
    }

    private String key(String traceId, String query, String category, int limit, String scopeKey) {
        return Objects.toString(traceId, "")
                + "|" + Objects.toString(query, "")
                + "|" + Objects.toString(category, "")
                + "|" + limit
                + "|" + Objects.toString(scopeKey, "");
    }
}
