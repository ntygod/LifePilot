package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.McpTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.search.cache.SessionSearchMemo;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.tier1.Tier1Service;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolSearchService 查询过滤测试。
 *
 * <p>FTS5 BM25 无法 mock，使用 sqlite in-memory + SingleConnectionDataSource 建真实 FTS 表；
 * 与项目既有 JDBC 集成测试模式保持一致。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ToolSearchService_查询过滤测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DynamicToolRegistry registry;
    private Tier1Service tier1Service;
    private ToolSearchService searchService;
    private SearchResultCache searchCache;
    private SessionSearchMemo memo;

    @BeforeEach
    void setUp() {
        // 内存 SQLite + FTS5 表（schema 与 V15 保持同步）
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE tool_search_index USING fts5(
                    tool_id UNINDEXED,
                    description,
                    tags,
                    actions,
                    category,
                    tokenize = 'trigram'
                )
                """);

        registry = new DynamicToolRegistry(_ -> {});
        registerBuiltinTool("file.delete", "Delete a file from the specified path",
                List.of("delete", "file", "remove"), ToolCategory.ACTION);
        registerMcpTool("mcp.fs.delete", "Delete a file from the specified path",
                List.of("delete", "file", "remove"), ToolCategory.ACTION);
        registerMcpTool("mcp.docs.query", "Query documents from MCP server by filter",
                List.of("query", "documents", "database"), ToolCategory.STORAGE);
        registerBuiltinTool("file.read", "Read a file content from path",
                List.of("read", "file", "content"), ToolCategory.PERCEPTION);

        // 手工填 FTS 索引（模拟 ToolSearchIndexBuilder.build()）
        for (ToolContract tool : registry.getToolSnapshot()) {
            jdbcTemplate.update(
                    "INSERT INTO tool_search_index (tool_id, description, tags, actions, category) VALUES (?, ?, ?, ?, ?)",
                    tool.id(), tool.description(),
                    String.join(" ", tool.tags()), "", tool.category().name());
        }

        // file.read 模拟为 Tier 1，应被搜索排除
        tier1Service = mock(Tier1Service.class);
        when(tier1Service.getCurrentTier1Ids()).thenReturn(Set.of("file.read"));

        searchCache = new SearchResultCache(100, Duration.ofMinutes(5));
        memo = new SessionSearchMemo();
        // 小文档 FTS5 的 bm25 绝对值通常较小（<1），测试场景调低阈值到 0.1 让 HIGH 判定可触发
        var config = new ToolConfigProperties.Search();
        config.setBm25ConfidenceThreshold(0.1);
        searchService = new ToolSearchService(
                jdbcTemplate, registry, new ToolSearchQuerySanitizer(),
                tier1Service, searchCache, memo, config, new SimpleMeterRegistry(), null);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void 搜索结果排除核心工具且返回非核心Java原生工具() {
        ReactAgentState state = sampleState("trace-1", Set.of(), null);
        ToolSearchResult result = searchService.search(state, "delete file", null, 5);

        assertThat(result.results()).extracting(ToolSearchHit::id)
                .contains("file.delete", "mcp.fs.delete")
                .doesNotContain("file.read", "tool.search");
    }

    @Test
    void 搜索结果排除已发现工具() {
        ReactAgentState state = sampleState("trace-1", Set.of("mcp.docs.query"), null);
        ToolSearchResult result = searchService.search(state, "query documents", null, 5);

        assertThat(result.results())
                .noneMatch(h -> h.id().equals("mcp.docs.query"));
    }

    @Test
    void 普通搜索_返回匹配工具_HIGH置信度() {
        ReactAgentState state = sampleState("trace-1", Set.of(), null);
        ToolSearchResult result = searchService.search(state, "delete file", null, 5);

        assertThat(result.results()).extracting(ToolSearchHit::id).contains("mcp.fs.delete");
        assertThat(result.confidence()).isEqualTo(ToolSearchConfidence.HIGH);
    }

    @Test
    void 零结果_返回NONE置信度并带hint() {
        ReactAgentState state = sampleState("trace-1", Set.of(), null);
        ToolSearchResult result = searchService.search(state, "xyzzyxwv", null, 5);

        assertThat(result.confidence()).isEqualTo(ToolSearchConfidence.NONE);
        assertThat(result.hint()).contains("无匹配");
    }

    @Test
    void 同query调用两次_第二次命中LayerC_不再查FTS5() {
        ReactAgentState state = sampleState("trace-1", Set.of(), null);
        searchService.search(state, "delete", null, 5);
        // 清空 FTS 表：若真的走 FTS5 会拿到空；命中 Layer C memoize 则仍有结果
        jdbcTemplate.update("DELETE FROM tool_search_index");
        ToolSearchResult r2 = searchService.search(state, "delete", null, 5);

        assertThat(r2.results()).isNotEmpty();
    }

    @Test
    void allowedToolIds非空时_仅返回白名单内的工具() {
        ReactAgentState state = sampleState("trace-1", Set.of(), List.of("mcp.fs.delete"));
        ToolSearchResult result = searchService.search(state, "delete", null, 5);

        assertThat(result.results()).extracting(ToolSearchHit::id).containsOnly("mcp.fs.delete");
    }

    @Test
    void disabledToolIds非空时_搜索结果应排除禁用父级工具() {
        ReactAgentState state = sampleState("trace-disabled", Set.of(), null, List.of("mcp.fs"));
        ToolSearchResult result = searchService.search(state, "delete file", null, 5);

        assertThat(result.results()).extracting(ToolSearchHit::id)
                .contains("file.delete")
                .doesNotContain("mcp.fs.delete");
    }

    /** 注册一个最小可用的 BuiltinTool 到 registry。 */
    private void registerBuiltinTool(String id, String description, List<String> tags, ToolCategory cat) {
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id(id)
                .name(id)
                .description(description)
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT)
                .tags(tags)
                .category(cat)
                .actionMetadata(Map.of())
                .executor(input -> null)
                .build());
    }

    /** 注册一个最小可搜索的 MCP 工具到 registry。 */
    private void registerMcpTool(String id, String description, List<String> tags, ToolCategory cat) {
        registry.registerMcpTools("test-server", List.of(new McpTool(
                id,
                id,
                description,
                JsonSchema.empty(),
                JsonSchema.empty(),
                RiskLevel.LOW,
                true,
                ToolExecutionSemantics.generic(),
                ToolBudget.DEFAULT,
                tags,
                "test-server",
                id.substring(id.lastIndexOf('.') + 1),
                "test-server"
        )));
    }

    /**
     * 构造一个最小 ReactAgentState mock。
     *
     * <p>ReactAgentState 是 record（final），但 Mockito 5.x 默认使用 inline mock maker
     * 可直接 mock final 类；比起 full AgentRequest + Budget 链路构造更干净。</p>
     */
    private ReactAgentState sampleState(String traceId, Set<String> discovered, List<String> allowed) {
        return sampleState(traceId, discovered, allowed, null);
    }

    private ReactAgentState sampleState(String traceId,
                                        Set<String> discovered,
                                        List<String> allowed,
                                        List<String> disabled) {
        ReactAgentState state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn(traceId);
        when(state.discoveredToolIds()).thenReturn(discovered);
        when(state.allowedToolIds()).thenReturn(allowed);
        when(state.disabledToolIds()).thenReturn(disabled);
        return state;
    }
}
