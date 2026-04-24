package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.search.cache.SessionSearchMemo;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.tier1.Tier1Service;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolSearchService Micrometer 指标接入测试。
 *
 * <p>验证 invocations / cache_hit / empty_results / low_confidence 等 Counter
 * 在对应代码路径上被正确递增；Timer 的 duration 通过 invocations 间接覆盖。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ToolSearchMetrics_接入测试 {

    private ToolSearchService searchService;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        var jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE tool_search_index USING fts5(
                    tool_id UNINDEXED, description, tags, actions, category,
                    tokenize = 'unicode61 remove_diacritics 2'
                )""");

        var toolRegistry = new DynamicToolRegistry(e -> {});
        toolRegistry.registerBuiltinTool(BuiltinTool.builder()
                .id("file.manage")
                .name("文件管理")
                .description("Manage files and directories with move, copy, delete, mkdir operations")
                .tags(List.of("manage", "file", "move", "copy", "delete"))
                .category(ToolCategory.ACTION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .executor(in -> null)
                .build());
        jdbcTemplate.update(
                "INSERT INTO tool_search_index (tool_id, description, tags, actions, category) VALUES (?, ?, ?, ?, ?)",
                "file.manage",
                "Manage files and directories with move, copy, delete, mkdir operations",
                "manage file move copy delete",
                "",
                "ACTION");

        var tier1 = mock(Tier1Service.class);
        when(tier1.getCurrentTier1Ids()).thenReturn(Set.of());

        var config = new ToolConfigProperties.Search();
        config.setBm25ConfidenceThreshold(0.01);    // 低阈值确保 HIGH

        registry = new SimpleMeterRegistry();
        searchService = new ToolSearchService(
                jdbcTemplate, toolRegistry, new ToolSearchQuerySanitizer(),
                tier1, new SearchResultCache(100, Duration.ofMinutes(5)),
                new SessionSearchMemo(), config, registry);
    }

    @Test
    void 每次搜索_invocations计数递增() {
        var state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn("t1");
        when(state.activatedToolIds()).thenReturn(Set.of());
        when(state.allowedToolIds()).thenReturn(List.of());

        searchService.search(state, "delete", null, 3);
        searchService.search(state, "manage", null, 3);

        assertThat(registry.counter("tool_search.invocations").count()).isEqualTo(2);
    }

    @Test
    void 命中LayerC_cache_hit计数c标签() {
        var state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn("t1");
        when(state.activatedToolIds()).thenReturn(Set.of());
        when(state.allowedToolIds()).thenReturn(List.of());

        searchService.search(state, "delete", null, 3);
        searchService.search(state, "delete", null, 3);    // Layer C 命中

        assertThat(registry.counter("tool_search.cache_hit", "layer", "c").count()).isEqualTo(1);
    }

    @Test
    void 零结果_empty_results计数() {
        var state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn("t1");
        when(state.activatedToolIds()).thenReturn(Set.of());
        when(state.allowedToolIds()).thenReturn(List.of());

        searchService.search(state, "zzzzzznotatool", null, 3);

        assertThat(registry.counter("tool_search.empty_results").count()).isEqualTo(1);
    }
}
