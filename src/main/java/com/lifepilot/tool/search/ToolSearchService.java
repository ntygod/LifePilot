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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 工具搜索主服务：sanitize → 三层缓存 → FTS5 BM25 → 过滤 → 返回结果。
 *
 * <p>过滤规则：排除 Tier 1 + activatedToolIds + meta 工具（避免重复暴露）；
 * 当 allowedToolIds 非空（受限代理场景）时，结果只能来自该集合。</p>
 *
 * <p>Micrometer 指标：invocations / duration / cache_hit{layer=b,c} /
 * empty_results / low_confidence。Layer A（SchemaCache）在本服务未参与，
 * 故不统计；describe 侧的 schema 命中单独在 ToolDescribeService 记录。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchService {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchService.class);

    /** 工具搜索 meta 工具 ID 集合 — 避免自指（tools.search 搜到自己）。 */
    private static final Set<String> META_TOOL_IDS = Set.of(
            "tools.search", "tools.describe", "tools.list");

    private final JdbcTemplate jdbcTemplate;
    private final DynamicToolRegistry registry;
    private final ToolSearchQuerySanitizer sanitizer;
    private final Tier1Service tier1Service;
    private final SearchResultCache searchResultCache;
    private final SessionSearchMemo sessionMemo;
    private final ToolConfigProperties.Search config;

    // ── Micrometer 指标 ──
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
            MeterRegistry meterRegistry) {
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
    }

    /**
     * 搜索工具。
     *
     * @param state ReactAgentState（含 traceId / activatedToolIds / allowedToolIds），可为 null
     * @param rawQuery LLM 生成的自然语言 query
     * @param category 可选 category 过滤
     * @param limitArg 可选 limit（默认用 config.defaultLimit，上限 config.maxLimit）
     * @return 结果（永不为 null）
     */
    public ToolSearchResult search(
            @Nullable ReactAgentState state,
            String rawQuery,
            @Nullable String category,
            @Nullable Integer limitArg) {

        invocationsCounter.increment();
        return durationTimer.record(() -> doSearch(state, rawQuery, category, limitArg));
    }

    /** 实际搜索逻辑 — 由 Timer.record 包裹以统计耗时。 */
    private ToolSearchResult doSearch(
            @Nullable ReactAgentState state,
            String rawQuery,
            @Nullable String category,
            @Nullable Integer limitArg) {

        int limit = Math.min(
                limitArg == null ? config.getDefaultLimit() : limitArg,
                config.getMaxLimit());

        String traceId = state == null ? null : state.traceId();

        // Layer C 会话级 memoize
        if (traceId != null) {
            var layerC = sessionMemo.get(traceId, rawQuery, category, limit);
            if (layerC.isPresent()) {
                cacheHitC.increment();
                log.debug("搜索命中 Layer C: traceId={}, query={}", traceId, rawQuery);
                return layerC.get();
            }
        }

        // Layer B 全局缓存
        var layerB = searchResultCache.get(rawQuery, category, limit);
        if (layerB.isPresent()) {
            cacheHitB.increment();
            if (traceId != null) {
                sessionMemo.put(traceId, rawQuery, category, limit, layerB.get());
            }
            log.debug("搜索命中 Layer B: query={}", rawQuery);
            return layerB.get();
        }

        // FTS5 查询表达式
        String matchExpr = sanitizer.sanitize(rawQuery);
        if (matchExpr.isEmpty()) {
            emptyResultsCounter.increment();
            return ToolSearchResult.empty();
        }

        // 构建排除集：Tier 1 + activated + meta
        Set<String> excluded = new HashSet<>();
        excluded.addAll(tier1Service.getCurrentTier1Ids());
        Set<String> activated = state != null && state.activatedToolIds() != null
                ? state.activatedToolIds() : Set.of();
        excluded.addAll(activated);
        excluded.addAll(META_TOOL_IDS);

        // allowedToolIds 是 List<String>，转为 Set 以便 contains 查询；为 null/空表示不限制
        Set<String> allowedScope = null;
        if (state != null && state.allowedToolIds() != null && !state.allowedToolIds().isEmpty()) {
            allowedScope = Set.copyOf(state.allowedToolIds());
        }

        // 多取一些，过滤后再截断到 limit
        List<FtsRow> rows = executeFts(matchExpr, category, limit * 3);

        final Set<String> finalAllowedScope = allowedScope;
        List<ToolSearchHit> hits = rows.stream()
                .filter(r -> !excluded.contains(r.toolId()))
                .filter(r -> finalAllowedScope == null || finalAllowedScope.contains(r.toolId()))
                .map(this::toHit)
                .filter(Objects::nonNull)
                .limit(limit)
                .toList();

        ToolSearchConfidence confidence;
        String hint = null;
        if (hits.isEmpty()) {
            confidence = ToolSearchConfidence.NONE;
            emptyResultsCounter.increment();
            hint = "No tools matched. Try broader keywords or call tools.list(category) to browse by category.";
        } else if (hits.get(0).score() < config.getBm25ConfidenceThreshold()) {
            confidence = ToolSearchConfidence.LOW;
            lowConfidenceCounter.increment();
            hint = "Low confidence match. Consider refining keywords or checking tools.list(category).";
        } else {
            confidence = ToolSearchConfidence.HIGH;
        }

        ToolSearchResult result = new ToolSearchResult(hits, rows.size(), confidence, hint);

        // 回写两层缓存
        searchResultCache.put(rawQuery, category, limit, result);
        if (traceId != null) {
            sessionMemo.put(traceId, rawQuery, category, limit, result);
        }
        return result;
    }

    /** 执行 FTS5 查询，返回按 BM25 rank 排序的 rows（bm25 原始值越小越相关）。 */
    private List<FtsRow> executeFts(String matchExpr, @Nullable String category, int limit) {
        if (category != null && !category.isBlank()) {
            return jdbcTemplate.query("""
                    SELECT tool_id, bm25(tool_search_index) AS score
                    FROM tool_search_index
                    WHERE tool_search_index MATCH ? AND category = ?
                    ORDER BY score
                    LIMIT ?
                    """,
                    (rs, i) -> new FtsRow(rs.getString("tool_id"), rs.getDouble("score")),
                    matchExpr, category, limit);
        }
        return jdbcTemplate.query("""
                SELECT tool_id, bm25(tool_search_index) AS score
                FROM tool_search_index
                WHERE tool_search_index MATCH ?
                ORDER BY score
                LIMIT ?
                """,
                (rs, i) -> new FtsRow(rs.getString("tool_id"), rs.getDouble("score")),
                matchExpr, limit);
    }

    /** 将 FTS 行解析为 ToolSearchHit；注册表中不存在时返回 null。 */
    @Nullable
    private ToolSearchHit toHit(FtsRow row) {
        return registry.resolve(row.toolId())
                .map(tool -> new ToolSearchHit(
                        tool.id(),
                        tool.description() == null ? "" : tool.description(),
                        tool.category() == null ? "" : tool.category().name(),
                        // FTS5 bm25() 越小越相关；取绝对值转为"越大越好"供 LLM 判断
                        Math.abs(row.score()),
                        extractActions(tool)
                ))
                .orElse(null);
    }

    private List<String> extractActions(ToolContract tool) {
        if (!(tool instanceof BuiltinTool builtin) || builtin.actionMetadata() == null) {
            return List.of();
        }
        return new ArrayList<>(builtin.actionMetadata().keySet());
    }

    /** FTS5 查询中间结果。 */
    private record FtsRow(String toolId, double score) {}
}
