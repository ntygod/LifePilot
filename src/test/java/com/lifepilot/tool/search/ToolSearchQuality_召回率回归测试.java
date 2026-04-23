package com.lifepilot.tool.search;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
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
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具搜索召回率回归测试。
 *
 * <p>使用内存 SQLite + 真实 FTS5 索引 + 真实 BM25 打分；工具定义从生产 ToolProvider
 * 的 tags / description 镜像过来（手动同步），覆盖全部 Tier 2 工具。对固定
 * query → expected_top_3 fixture 集跑 {@link ToolSearchService#search}，
 * top-3 命中至少一个期望工具即算召回成功；整体召回率 ≥ 85% 视为通过。
 * 失败时打印每条失败 query 的期望与实际 top-3，便于补 tags 调优。</p>
 *
 * <p>之所以不用 @SpringBootTest：启动完整 Spring 上下文需要 workflow / meta / media
 * / llm 等模块完整装配，依赖链过长且与搜索召回质量无关。用 JDBC 自建 FTS + 镜像真实
 * 工具定义，既保留了 FTS5 BM25 的真实行为，又避免了启动耗时与依赖耦合。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ToolSearchQuality_召回率回归测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DynamicToolRegistry registry;
    private Tier1Service tier1Service;
    private ToolSearchService searchService;
    private SearchResultCache searchCache;
    private SessionSearchMemo memo;

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
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """);

        registry = new DynamicToolRegistry(_ -> {});
        registerAllProductionTools();

        // 手工填 FTS 索引（镜像 ToolSearchIndexBuilder.build()）
        for (ToolContract tool : registry.getToolSnapshot()) {
            jdbcTemplate.update(
                    "INSERT INTO tool_search_index (tool_id, description, tags, actions, category) VALUES (?, ?, ?, ?, ?)",
                    tool.id(), tool.description(),
                    String.join(" ", tool.tags()),
                    "",
                    tool.category().name());
        }

        // Tier 1 pinned 列表（镜像 application.yml 默认值）
        tier1Service = mock(Tier1Service.class);
        when(tier1Service.getCurrentTier1Ids()).thenReturn(Set.of(
                "tools.search", "tools.describe", "tools.list",
                "file.read", "file.write", "file.list",
                "web.search", "web.fetch", "shell.exec",
                "memory", "knowledge.search"));

        searchCache = new SearchResultCache(100, Duration.ofMinutes(5));
        memo = new SessionSearchMemo();
        ToolConfigProperties.Search config = new ToolConfigProperties.Search();
        searchService = new ToolSearchService(
                jdbcTemplate, registry, new ToolSearchQuerySanitizer(),
                tier1Service, searchCache, memo, config, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void 固定fixture集_top3召回率不低于85percent() throws Exception {
        Map<String, Object> root;
        try (InputStream is = getClass().getResourceAsStream("/tool-search-fixtures.yaml")) {
            assertThat(is).as("fixture yaml 必须存在").isNotNull();
            root = new Yaml().load(is);
        }
        List<Map<String, Object>> fixtures = (List<Map<String, Object>>) root.get("fixtures");
        assertThat(fixtures).isNotEmpty();

        ReactAgentState state = emptyState();

        int hits = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> fx : fixtures) {
            String query = (String) fx.get("query");
            List<String> expected = (List<String>) fx.get("expected_top_3");

            ToolSearchResult result = searchService.search(state, query, null, 3);
            List<String> actualIds = result.results().stream()
                    .map(ToolSearchHit::id)
                    .toList();

            if (!Collections.disjoint(actualIds, expected)) {
                hits++;
            } else {
                failures.add("query=\"" + query + "\", expected=" + expected
                        + ", actual=" + actualIds);
            }
        }

        double recall = (double) hits / fixtures.size();
        System.out.printf("【召回率回归】%d / %d = %.2f%%%n",
                hits, fixtures.size(), recall * 100);
        if (!failures.isEmpty()) {
            System.out.println("【失败样本】");
            failures.forEach(System.out::println);
        }

        assertThat(recall)
                .as("top-3 召回率应 ≥ 85%（失败样本见控制台输出便于补 tags）")
                .isGreaterThanOrEqualTo(0.85);
    }

    /**
     * 注册全部 Tier 1 + Tier 2 工具（镜像生产 ToolProvider 的 description / tags）。
     *
     * <p>保持与生产 tags 同步是本测试的约定；补 tags 时两边同时改。</p>
     */
    private void registerAllProductionTools() {
        // Tier 1 pinned —— 进搜索索引但会被 service 过滤掉；放进去是为了让 FTS 更真实
        reg("tools.search", "Search the tool registry by natural-language query.",
                List.of("meta", "search", "discovery"), ToolCategory.INTROSPECTION);
        reg("tools.describe", "Return full JSON schemas for the given tool ids.",
                List.of("meta", "describe", "schema"), ToolCategory.INTROSPECTION);
        reg("tools.list", "List all available tool ids grouped by category.",
                List.of("meta", "list", "category"), ToolCategory.INTROSPECTION);
        reg("file.read", "Read a file or load a skill manual. Accepts path (local file; docx/xlsx/pptx/pdf/md/csv auto-parsed), attachmentId (attachment; office formats auto-extracted), or skill (comma-separated skill names to load and auto-activate).",
                List.of("infrastructure", "read", "file", "load", "fetch", "content", "document"),
                ToolCategory.PERCEPTION);
        reg("file.write", "Create a new file or overwrite/append content to an existing file. mode=write atomically overwrites (default); mode=append adds to the end. Parent directories are created automatically.",
                List.of("infrastructure", "write", "file", "save", "create", "append", "overwrite"),
                ToolCategory.ACTION);
        reg("file.list", "Query filesystem information. action=list enumerates directory entries; action=search recursively greps for content; action=info returns file or directory metadata.",
                List.of("infrastructure", "list", "file", "browse", "search", "directory", "enumerate", "info"),
                ToolCategory.PERCEPTION);
        reg("web.search", "Search the web via a configured provider.",
                List.of("infrastructure", "web", "search", "query"), ToolCategory.PERCEPTION);
        reg("web.fetch", "Fetch a URL and return its content.",
                List.of("infrastructure", "web", "fetch", "url", "download"), ToolCategory.PERCEPTION);
        reg("shell.exec", "Execute a shell command synchronously and return stdout/stderr.",
                List.of("infrastructure", "shell", "exec", "command", "bash", "terminal", "run", "execute", "script"),
                ToolCategory.ACTION);
        reg("memory", "Read or write the agent memory layers.",
                List.of("infrastructure", "memory", "recall", "save"), ToolCategory.STORAGE);
        reg("knowledge.search", "Search the knowledge base with hybrid retrieval.",
                List.of("infrastructure", "knowledge", "search", "rag"), ToolCategory.PERCEPTION);

        // Tier 2 file.* —— 镜像 FileToolProvider / FileEditToolProvider
        reg("file.edit", "Precisely modify file content via line-level operations (insert/replace/delete) or text match replace.",
                List.of("infrastructure", "edit", "file", "modify", "patch", "replace", "update"),
                ToolCategory.ACTION);
        reg("file.manage", "Move, copy, delete, or create files and directories. Supports batch operations.",
                List.of("infrastructure", "manage", "move", "copy", "delete", "create", "mkdir", "file", "directory"),
                ToolCategory.ACTION);
        reg("file.undo", "Undo the most recent file edit (revert to previous snapshot).",
                List.of("infrastructure", "undo", "revert", "rollback", "file", "edit"),
                ToolCategory.ACTION);
        reg("file.redo", "Redo the most recent undone file edit.",
                List.of("infrastructure", "redo", "reapply", "file", "edit"),
                ToolCategory.ACTION);
        reg("file.diff", "Compute a unified diff between two files or two versions of a file.",
                List.of("infrastructure", "diff", "compare", "file", "difference", "changes", "inspect"),
                ToolCategory.PERCEPTION);

        // Tier 2 shell.* —— 镜像 ShellToolProvider
        reg("shell.process", "Manage background shell processes (tmux-like session management).",
                List.of("infrastructure", "process", "background", "session", "tmux", "shell", "manage", "kill", "signal"),
                ToolCategory.ACTION);

        // Tier 2 git.* —— 镜像 GitToolProvider
        reg("git.query", "Query the git repository for status / diff / log / blame information.",
                List.of("infrastructure", "git", "query", "status", "diff", "log", "blame", "repository"),
                ToolCategory.PERCEPTION);
        reg("git.mutate", "Mutate the git repository: commit / stash / branch operations.",
                List.of("infrastructure", "git", "commit", "stash", "branch", "write", "repository"),
                ToolCategory.ACTION);

        // Tier 2 browser —— 镜像 BrowserToolProvider
        reg("browser", "Automate a Playwright-controlled browser: navigate, click, fill, screenshot.",
                List.of("infrastructure", "browser", "automation", "playwright", "navigate", "click", "screenshot", "web"),
                ToolCategory.ACTION);

        // Tier 2 code.* —— 镜像 CodeToolProvider / CodeKernelToolProvider
        reg("code.execute", "Execute Python / JavaScript code in an isolated sandbox.",
                List.of("infrastructure", "code", "execute", "run", "sandbox", "script", "python", "eval"),
                ToolCategory.ACTION);
        reg("code.kernel.list", "List all active code-execution kernel sessions.",
                List.of("infrastructure", "kernel", "list", "code", "enumerate", "session"),
                ToolCategory.INTROSPECTION);
        reg("code.kernel.reset", "Reset (restart) a code-execution kernel session.",
                List.of("infrastructure", "kernel", "reset", "restart", "code", "clean", "clear"),
                ToolCategory.ACTION);
        reg("code.kernel.inspect", "Inspect variables and execution state of a kernel session.",
                List.of("infrastructure", "kernel", "inspect", "variables", "state", "code", "debug"),
                ToolCategory.PERCEPTION);

        // Tier 2 datastore / cron / notify —— 镜像 StorageToolProvider / TaskToolProvider / NotifyToolProvider
        reg("datastore", "Query datastore collections: find / aggregate / count / insert / update / delete documents.",
                List.of("datastore", "storage", "database", "collection", "query", "crud", "aggregate", "document"),
                ToolCategory.STORAGE);
        reg("cron", "Schedule cron tasks: create / update / delete recurring jobs.",
                List.of("cron", "schedule", "task", "automation", "timer", "job", "recurring"),
                ToolCategory.ACTION);
        reg("notify", "Send notifications / messages / alerts to the user.",
                List.of("infrastructure", "notify", "message", "send", "alert", "notification", "push"),
                ToolCategory.INTERACTION);

        // Tier 2 document.* —— 镜像 DocumentToolProvider / DocumentEditToolProvider
        reg("document.create", "Generate a new docx / xlsx / pptx document from a template or prompt.",
                List.of("infrastructure", "document", "create", "docx", "xlsx", "pptx", "word", "excel", "powerpoint", "generate"),
                ToolCategory.ACTION);
        reg("document.edit", "Patch / rollback / commit versions of a docx / xlsx / pptx document.",
                List.of("infrastructure", "document", "edit", "docx", "xlsx", "patch", "diff", "commit", "rollback", "version"),
                ToolCategory.ACTION);

        // Tier 2 workflow —— 镜像 WorkflowToolProvider
        reg("workflow", "Manage workflow orchestrations: start / stop / inspect / cancel running flows.",
                List.of("workflow", "process", "orchestration", "manage", "flow", "automation"),
                ToolCategory.ACTION);

        // Tier 2 ui.emit —— 镜像 UiEmitToolProvider
        reg("ui.emit", "Render an interactive UI widget or component for the frontend.",
                List.of("ui", "render", "component", "interactive", "emit", "frontend", "widget"),
                ToolCategory.INTERACTION);

        // Tier 2 system.status / spawn_workers / generate_skill
        reg("system.status", "Query system runtime health and status.",
                List.of("infrastructure", "system", "status", "health", "runtime", "introspection", "query"),
                ToolCategory.INTROSPECTION);
        reg("spawn_workers", "Spawn multiple sub-agents to handle tasks in parallel batches.",
                List.of("multiagent", "parallel", "worker", "spawn", "concurrent", "batch"),
                ToolCategory.ACTION);
        reg("generate_skill", "Generate a new skill definition to expand agent capabilities.",
                List.of("skill", "generate", "create", "discovery", "capability"),
                ToolCategory.EXTENSION);
    }

    /** 简化的 BuiltinTool 注册工厂方法。 */
    private void reg(String id, String description, List<String> tags, ToolCategory category) {
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

    private ReactAgentState emptyState() {
        ReactAgentState state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn("quality-regression");
        when(state.activatedToolIds()).thenReturn(Set.of());
        when(state.allowedToolIds()).thenReturn(List.of());
        return state;
    }
}
