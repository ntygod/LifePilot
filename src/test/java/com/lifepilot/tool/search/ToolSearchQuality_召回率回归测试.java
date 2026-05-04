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
 * 工具搜索召回率回归测试。
 *
 * <p>{@code tool.search} 负责发现未直接注入 Agent 的 Java 原生工具和 MCP 外部工具。
 * 本测试用真实 SQLite FTS5 覆盖一组典型能力，防止过滤与召回规则回退。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ToolSearchQuality_召回率回归测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DynamicToolRegistry registry;
    private ToolSearchService searchService;

    @BeforeEach
    void setUp() {
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
        registerBuiltinTool("file.read", "读取本地文件内容", List.of("读取", "文件"), ToolCategory.PERCEPTION);
        registerBuiltinTool("file.write", "写入本地文件内容，创建或覆盖指定路径的文本文件。",
                List.of("写入", "文件", "write", "file"), ToolCategory.ACTION);
        registerMcpTool("mcp.desktop.screenshot", "桌面自动化截图，捕获屏幕画面并返回图片。",
                List.of("桌面", "截图", "屏幕", "desktop", "screenshot"), ToolCategory.PERCEPTION);
        registerMcpTool("mcp.github.create_issue", "在 GitHub 仓库创建 issue 并设置标题、正文和标签。",
                List.of("github", "issue", "创建", "仓库", "缺陷"), ToolCategory.ACTION);
        registerMcpTool("mcp.calendar.create_event", "创建日历事件、会议和提醒，支持时间、参会人和地点。",
                List.of("日历", "会议", "提醒", "calendar", "event"), ToolCategory.ACTION);
        registerMcpTool("mcp.slack.send_message", "向 Slack 频道或用户发送消息通知。",
                List.of("slack", "通知", "消息", "频道", "send"), ToolCategory.INTERACTION);
        registerMcpTool("mcp.database.query", "查询外部数据库，执行只读 SQL 并返回表格结果。",
                List.of("数据库", "查询", "SQL", "表格", "database"), ToolCategory.PERCEPTION);

        for (ToolContract tool : registry.getToolSnapshot()) {
            jdbcTemplate.update(
                    "INSERT INTO tool_search_index (tool_id, description, tags, actions, category) VALUES (?, ?, ?, ?, ?)",
                    tool.id(), tool.description(), String.join(" ", tool.tags()), "", tool.category().name());
        }

        Tier1Service tier1Service = mock(Tier1Service.class);
        when(tier1Service.getCurrentTier1Ids()).thenReturn(Set.of("file.read", "tool.search"));

        ToolConfigProperties.Search config = new ToolConfigProperties.Search();
        config.setBm25ConfidenceThreshold(0.1);
        searchService = new ToolSearchService(
                jdbcTemplate,
                registry,
                new ToolSearchQuerySanitizer(),
                tier1Service,
                new SearchResultCache(100, Duration.ofMinutes(5)),
                new SessionSearchMemo(),
                config,
                new SimpleMeterRegistry(),
                null
        );
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void 固定fixture集_top3召回率不低于80percent() {
        List<QueryCase> fixtures = List.of(
                new QueryCase("写入本地文件", "file.write"),
                new QueryCase("桌面自动化截图", "mcp.desktop.screenshot"),
                new QueryCase("GitHub 仓库 issue", "mcp.github.create_issue"),
                new QueryCase("创建日历事件", "mcp.calendar.create_event"),
                new QueryCase("Slack 频道消息", "mcp.slack.send_message"),
                new QueryCase("只读 SQL 数据库", "mcp.database.query")
        );
        ReactAgentState state = emptyState();

        long hits = fixtures.stream()
                .filter(fixture -> searchService.search(state, fixture.query(), null, 3)
                        .results().stream()
                        .map(ToolSearchHit::id)
                        .anyMatch(fixture.expectedToolId()::equals))
                .count();

        double recall = (double) hits / fixtures.size();
        assertThat(recall).isGreaterThanOrEqualTo(0.8);
    }

    private void registerBuiltinTool(String id, String description, List<String> tags, ToolCategory category) {
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
                .category(category)
                .actionMetadata(Map.of())
                .executor(input -> null)
                .build());
    }

    private void registerMcpTool(String id, String description, List<String> tags, ToolCategory category) {
        registry.registerMcpTools("quality", List.of(new McpTool(
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
                "quality",
                id.substring(id.lastIndexOf('.') + 1),
                "quality"
        )));
    }

    private ReactAgentState emptyState() {
        ReactAgentState state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn("quality-regression");
        when(state.discoveredToolIds()).thenReturn(Set.of());
        when(state.allowedToolIds()).thenReturn(List.of());
        return state;
    }

    private record QueryCase(String query, String expectedToolId) {}
}
