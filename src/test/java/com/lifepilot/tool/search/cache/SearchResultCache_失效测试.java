package com.lifepilot.tool.search.cache;

import com.lifepilot.tool.search.ToolSearchConfidence;
import com.lifepilot.tool.search.ToolSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultCache_失效测试 {

    private final SearchResultCache cache = new SearchResultCache(100, Duration.ofMinutes(5));

    @Test
    void 相同query_cat_limit命中同一缓存条目() {
        ToolSearchResult r1 = sample();
        cache.put("delete files", "ACTION", 5, r1);
        assertThat(cache.get("delete files", "ACTION", 5)).isPresent();
        assertThat(cache.get("delete files", "ACTION", 5).get()).isSameAs(r1);
    }

    @Test
    void 不同参数组合_各自独立() {
        cache.put("a", null, 5, sample());
        cache.put("a", "STORAGE", 5, sample());
        assertThat(cache.get("a", null, 5)).isPresent();
        assertThat(cache.get("a", "STORAGE", 5)).isPresent();
        assertThat(cache.get("a", "ACTION", 5)).isEmpty();
    }

    @Test
    void invalidateAll_清空所有条目() {
        cache.put("a", null, 5, sample());
        cache.put("b", null, 5, sample());
        cache.invalidateAll();
        assertThat(cache.get("a", null, 5)).isEmpty();
        assertThat(cache.get("b", null, 5)).isEmpty();
    }

    private ToolSearchResult sample() {
        return new ToolSearchResult(List.of(), 0, ToolSearchConfidence.NONE, null);
    }
}
