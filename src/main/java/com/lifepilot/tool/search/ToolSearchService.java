package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.search.cache.SessionSearchMemo;
import com.lifepilot.tool.tier1.Tier1Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工具搜索主服务：sanitize → 三层缓存 → BM25 + 语义搜索 RRF 融合 → 过滤 → 返回结果（含 inputSchema）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchService {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchService.class);

    private static final Set<String> META_TOOL_IDS = Set.of("tool.search");

    private final JdbcTemplate jdbcTemplate;
    private final DynamicToolRegistry registry;
    private final ToolSearchQuerySanitizer sanitizer;
    private final Tier1Service tier1Service;
    private final SearchResultCache searchResultCache;
    private final SessionSearchMemo sessionMemo;
    private final ToolConfigProperties.Search config;
    @Nullable
    private final ToolEmbeddingIndex embeddingIndex;

    private final Counter invocationsCounter;
    private final Timer durationTimer;
    private final Counter cacheHitB;
    private final Counter cacheHitC;
    private final Counter emptyResultsCounter;
    private final Counter lowConfidenceCounter;

    public ToolSearchService(
            JdbcTemplate jdbcTemplate,
            DynamicToolRegistry registry,
            ToolSearchQuerySanitizer sanitizer,
            Tier1Service tier1Service,
            SearchResultCache searchResultCache,
            SessionSearchMemo sessionMemo,
            ToolConfigProperties.Search config,
            MeterRegistry meterRegistry,
            @Nullable ToolEmbeddingIndex embeddingIndex) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        this.sanitizer = sanitizer;
        this.tier1Service = tier1Service;
        this.searchResultCache = searchResultCache;
        this.sessionMemo = sessionMemo;
        this.config = config;

        this.invocationsCounter = meterRegistry.counter("tool_search.invocations");
        this.durationTimer = meterRegistry.timer("tool_search.duration");
        this.cacheHitB = meterRegistry.counter("tool_search.cache_hit", "layer", "b");
        this.cacheHitC = meterRegistry.counter("tool_search.cache_hit", "layer", "c");
        this.emptyResultsCounter = meterRegistry.counter("tool_search.empty_results");
        this.lowConfidenceCounter = meterRegistry.counter("tool_search.low_confidence");
        this.embeddingIndex = embeddingIndex;
    }

    public ToolSearchResult search(
            @Nullable ReactAgentState state,
            String rawQuery,
            @Nullable String category,
            @Nullable Integer limitArg) {
        invocationsCounter.increment();
        return durationTimer.record(() -> doSearch(state, rawQuery, category, limitArg));
    }

    private ToolSearchResult doSearch(
            @Nullable ReactAgentState state,
            String rawQuery,
            @Nullable String category,
            @Nullable Integer limitArg) {

        int limit = Math.min(
                limitArg == null ? config.getDefaultLimit() : limitArg,
                config.getMaxLimit());

        String traceId = state == null ? null : state.traceId();
        SearchScope searchScope = buildSearchScope(state);
        String scopeKey = searchScope.cacheKey();

        // Layer C / B 缓存
        if (traceId != null) {
            var layerC = sessionMemo.get(traceId, rawQuery, category, limit, scopeKey);
            if (layerC.isPresent()) { cacheHitC.increment(); return layerC.get(); }
        }
        var layerB = searchResultCache.get(rawQuery, category, limit, scopeKey);
        if (layerB.isPresent()) {
            cacheHitB.increment();
            if (traceId != null) sessionMemo.put(traceId, rawQuery, category, limit, scopeKey, layerB.get());
            return layerB.get();
        }

        String matchExpr = sanitizer.sanitize(rawQuery);
        if (matchExpr.isEmpty()) { emptyResultsCounter.increment(); return ToolSearchResult.empty(); }

        // BM25
        List<FtsRow> bm25Rows = executeFts(matchExpr, category, limit * 3);

        // 语义搜索（懒构建索引；向量服务不可用时静默降级为 BM25）
        List<ToolEmbeddingIndex.SemanticHit> semanticRows = embeddingIndex != null
                ? embeddingIndex.search(rawQuery, category, limit * 3)
                : List.of();

        // RRF 融合
        final Set<String> finalAllowedScope = searchScope.allowedScope();
        final Set<String> excluded = searchScope.excluded();
        final Set<String> disabled = searchScope.disabled();
        List<String> mergedIds = fuseRrf(bm25Rows, semanticRows, 60, Math.max(limit * 3, 10));

        List<ToolSearchHit> hits = mergedIds.stream()
                .filter(this::isDiscoverableTool)
                .filter(id -> !excluded.contains(id))
                .filter(id -> !isToolDisabled(id, disabled))
                .filter(id -> finalAllowedScope == null || finalAllowedScope.contains(id))
                .map(this::toHitById)
                .filter(Objects::nonNull)
                .limit(limit)
                .toList();

        ToolSearchConfidence confidence;
        String hint = null;
        if (hits.isEmpty()) {
            confidence = ToolSearchConfidence.NONE;
            emptyResultsCounter.increment();
            hint = "无匹配工具。请尝试更宽泛的关键词或换不同的描述。";
        } else {
            confidence = ToolSearchConfidence.HIGH;
        }

        int totalMatched = bm25Rows.size() + semanticRows.size();
        ToolSearchResult result = new ToolSearchResult(hits, totalMatched, confidence, hint);
        searchResultCache.put(rawQuery, category, limit, scopeKey, result);
        if (traceId != null) sessionMemo.put(traceId, rawQuery, category, limit, scopeKey, result);
        return result;
    }

    private SearchScope buildSearchScope(@Nullable ReactAgentState state) {
        var excluded = new LinkedHashSet<String>();
        excluded.addAll(tier1Service.getCurrentTier1Ids());
        excluded.addAll(META_TOOL_IDS);
        if (state != null && state.discoveredToolIds() != null) {
            excluded.addAll(state.discoveredToolIds());
        }
        var disabled = new LinkedHashSet<String>();
        if (state != null && state.disabledToolIds() != null) {
            disabled.addAll(state.disabledToolIds());
        }

        Set<String> allowedScope = null;
        if (state != null && state.allowedToolIds() != null && !state.allowedToolIds().isEmpty()) {
            allowedScope = Set.copyOf(state.allowedToolIds());
        }

        String cacheKey = "excluded="
                + excluded.stream().sorted().collect(java.util.stream.Collectors.joining(","))
                + "|allowed="
                + (allowedScope == null
                ? "*"
                : allowedScope.stream().sorted().collect(java.util.stream.Collectors.joining(",")))
                + "|disabled="
                + disabled.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        return new SearchScope(Set.copyOf(excluded), allowedScope, Set.copyOf(disabled), cacheKey);
    }

    private boolean isToolDisabled(String toolId, Set<String> disabledToolIds) {
        if (disabledToolIds.isEmpty()) {
            return false;
        }
        if (disabledToolIds.contains(toolId)) {
            return true;
        }
        return disabledToolIds.stream()
                .anyMatch(disabledId -> toolId.startsWith(disabledId + "."));
    }

    private boolean isDiscoverableTool(String toolId) {
        return registry.resolve(toolId).isPresent();
    }

    private List<FtsRow> executeFts(String matchExpr, @Nullable String category, int limit) {
        if (category != null && !category.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT tool_id, bm25(tool_search_index) AS score
                    FROM tool_search_index WHERE tool_search_index MATCH ? AND category = ?
                    ORDER BY score LIMIT ?""",
                    (rs, i) -> new FtsRow(rs.getString("tool_id"), rs.getDouble("score")),
                    matchExpr, category, limit);
        }
        return jdbcTemplate.query("""
                SELECT tool_id, bm25(tool_search_index) AS score
                FROM tool_search_index WHERE tool_search_index MATCH ?
                ORDER BY score LIMIT ?""",
                (rs, i) -> new FtsRow(rs.getString("tool_id"), rs.getDouble("score")),
                matchExpr, limit);
    }

    @Nullable
    private ToolSearchHit toHitById(String toolId) {
        return registry.resolve(toolId)
                .map(tool -> new ToolSearchHit(
                        tool.id(),
                        tool.description() == null ? "" : tool.description(),
                        tool.category() == null ? "" : tool.category().name(),
                        0.0,
                        extractActions(tool),
                        tool.inputSchema() != null ? tool.inputSchema().toMap() : Map.of()
                ))
                .orElse(null);
    }

    private List<String> extractActions(ToolContract tool) {
        if (!(tool instanceof BuiltinTool builtin) || builtin.actionMetadata() == null) {
            return List.of();
        }
        return new ArrayList<>(builtin.actionMetadata().keySet());
    }

    private List<String> fuseRrf(List<FtsRow> bm25, List<ToolEmbeddingIndex.SemanticHit> semantic, int k, int limit) {
        var scores = new HashMap<String, Double>();
        var sortedBm25 = bm25.stream().sorted(Comparator.comparingDouble(FtsRow::score)).toList();
        for (int i = 0; i < sortedBm25.size(); i++) {
            scores.merge(sortedBm25.get(i).toolId(), 1.0 / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < semantic.size(); i++) {
            scores.merge(semantic.get(i).toolId(), 1.0 / (k + i + 1), Double::sum);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private record FtsRow(String toolId, double score) {}

    private record SearchScope(Set<String> excluded,
                               @Nullable Set<String> allowedScope,
                               Set<String> disabled,
                               String cacheKey) {}
}
