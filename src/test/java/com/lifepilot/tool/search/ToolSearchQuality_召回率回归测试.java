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
                    tokenize = 'trigram'
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
                "tools.search", "tools.describe",
                "file.read", "file.write", "file.list",
                "web.search", "web.fetch", "shell.exec",
                "memory", "knowledge.search",
                "skill.load"));

        searchCache = new SearchResultCache(100, Duration.ofMinutes(5));
        memo = new SessionSearchMemo();
        ToolConfigProperties.Search config = new ToolConfigProperties.Search();
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
     * <p>所有 description / tags 已中文化以匹配 production，FTS5 用 trigram tokenizer。
     * 保持与生产 tags 同步是本测试的约定；补 tags 时两边同时改。</p>
     */
    private void registerAllProductionTools() {
        // Tier 1 pinned —— 进搜索索引但会被 service 过滤掉
        reg("tools.search", "用关键词在工具注册表中按 BM25 排序找匹配。",
                List.of("搜索", "工具", "发现", "查找", "tools", "search"), ToolCategory.INTROSPECTION);
        reg("tools.describe", "批量返回指定工具 ID 的完整 JSON schema 与元数据。",
                List.of("详情", "工具", "schema", "describe", "tools"), ToolCategory.INTROSPECTION);
        reg("file.read", "读取本地文件或对话附件。docx/xlsx/pptx/pdf/md/csv 自动解析；仅可访问 skills 与 workspace 目录。",
                List.of("读取", "文件", "查看", "内容", "文档", "read", "file"), ToolCategory.PERCEPTION);
        reg("file.write", "创建或覆盖/追加写文件。mode=write 原子覆盖（默认），mode=append 追加；父目录自动创建。",
                List.of("写入", "文件", "保存", "创建", "追加", "覆盖", "write", "file"), ToolCategory.ACTION);
        reg("file.list", "查询文件系统：list 列目录、search 递归搜内容、info 看元数据。",
                List.of("列表", "文件", "目录", "搜索", "查找", "元数据", "list", "search", "directory"), ToolCategory.PERCEPTION);
        reg("web.search", "用关键词搜互联网，返回标题/URL/摘要。",
                List.of("搜索", "互联网", "网络", "查询", "search", "web", "internet"), ToolCategory.PERCEPTION);
        reg("web.fetch", "抓取 URL 内容或调外部 REST API。",
                List.of("抓取", "网页", "下载", "请求", "接口", "fetch", "web", "http", "api"), ToolCategory.PERCEPTION);
        reg("shell.exec", "执行 shell 命令。短命令同步；长服务用 background=true。",
                List.of("命令", "执行", "脚本", "终端", "shell", "exec", "command", "bash"), ToolCategory.ACTION);
        reg("memory", "搜索并管理用户长期记忆。",
                List.of("记忆", "回忆", "记住", "保存", "知识", "历史", "搜索", "memory", "recall"), ToolCategory.STORAGE);
        reg("knowledge.search", "按语义检索当前会话绑定的资料文档。",
                List.of("资料", "文档", "知识库", "检索", "搜索", "rag", "knowledge", "search"), ToolCategory.PERCEPTION);
        reg("skill.load", "按名字激活 1-3 个技能，返回完整指南并并入可见工具集。",
                List.of("技能", "激活", "加载", "指南", "skill", "load", "extension"), ToolCategory.EXTENSION);

        // Tier 2 file.*
        reg("file.edit", "精确编辑修改文件内容：行级 insert/replace/delete，或 search_replace 文本匹配替换。",
                List.of("编辑", "修改", "文件", "替换", "更新", "edit", "patch", "file"), ToolCategory.ACTION);
        reg("file.manage", "文件目录管理：移动文件、复制目录、删除文件、新建目录、重命名，支持批量操作。",
                List.of("管理", "移动", "复制", "删除", "重命名", "目录", "文件", "manage", "move", "copy", "delete"), ToolCategory.ACTION);
        reg("file.history", "文件编辑历史：undo 撤销最近一次编辑回滚快照；redo 重做被撤销的编辑；diff 输出 unified diff 对比文件版本。",
                List.of("撤销", "重做", "回滚", "差异", "对比", "文件", "编辑", "历史", "undo", "redo", "diff", "history", "file"), ToolCategory.ACTION);

        // Tier 2 shell.*
        reg("shell.process", "管理后台进程与 tmux 会话：list 列进程、output 读输出、write 写输入、kill 终止；session-* 操作 tmux 会话。",
                List.of("进程", "后台", "会话", "管理", "终止", "tmux", "process", "session", "shell", "kill"), ToolCategory.ACTION);

        // Tier 2 git.*
        reg("git.query", "查询 Git 仓库：查看 status 状态、diff 差异、log 提交日志、blame 行级追溯。",
                List.of("git", "仓库", "提交", "历史", "差异", "查询", "status", "diff", "log", "blame"), ToolCategory.PERCEPTION);
        reg("git.mutate", "Git 写操作：提交代码 commit、暂存 stash、分支 branch 管理。",
                List.of("git", "提交", "分支", "暂存", "推送", "commit", "stash", "branch", "push"), ToolCategory.ACTION);

        // Tier 2 browser
        reg("browser", "浏览器自动化：导航网页、点击、输入、网页截图、滚动、键盘、标签页（基于 Playwright）。",
                List.of("浏览器", "自动化", "导航", "点击", "截图", "browser", "playwright", "automation"), ToolCategory.ACTION);

        // Tier 2 code.*
        reg("code.execute", "在沙箱中执行代码，支持 Python / JavaScript 等脚本语言。",
                List.of("代码", "执行", "脚本", "沙箱", "code", "execute", "python", "javascript"), ToolCategory.ACTION);
        reg("code.kernel", "管理代码内核会话：list 列出活跃内核、reset 重置内核变量与已导入模块、inspect 查看内核变量与执行状态。",
                List.of("内核", "代码", "管理", "列表", "重置", "变量", "状态", "调试", "kernel", "code"), ToolCategory.ACTION);

        // Tier 2 cron / notify
        reg("cron", "定时任务调度：创建周期任务、列出任务、更新、删除 cron 任务。",
                List.of("定时", "任务", "调度", "自动化", "周期", "提醒", "cron", "schedule", "task", "timer"), ToolCategory.ACTION);
        reg("notify.send_message", "推送通知：发送提醒、消息、告警到当前渠道用户。",
                List.of("通知", "消息", "推送", "提醒", "notify", "message", "push"), ToolCategory.INTERACTION);

        // Tier 2 document.*
        reg("document.create", "创建文档（生成 Word/Excel/PowerPoint）：生成 docx 文档、生成 Excel 表格、生成 PPT 幻灯片，保存到本地。",
                List.of("文档", "生成", "Word", "Excel", "PPT", "幻灯片", "document", "docx", "xlsx", "pptx"), ToolCategory.ACTION);
        reg("document.edit", "编辑文档（docx/xlsx）：修改文档内容、提交工作副本、回滚文档版本、列版本历史；带锚点 patch 与版本管理。",
                List.of("文档", "编辑", "修改", "提交", "回滚", "版本", "document", "edit", "docx", "xlsx", "patch", "rollback"), ToolCategory.ACTION);

        // Tier 2 ui.render
        reg("ui.render", "渲染界面组件：向前端渲染交互式 UI 组件（按钮、表单、卡片、信号灯）。",
                List.of("界面", "渲染", "组件", "前端", "交互", "卡片", "ui", "render", "component", "frontend", "widget"), ToolCategory.INTERACTION);

        // Tier 2 system.status / spawn_workers
        reg("system.status", "查看系统状态：查询当前运行状态、版本、运行时长、健康指标。",
                List.of("系统", "状态", "健康", "运行", "查询", "system", "status", "health"), ToolCategory.INTROSPECTION);
        reg("spawn_workers", "派遣并行 Worker：开多个子 Agent 并行处理无共享状态的子任务，适用于调研、对比、批量处理。",
                List.of("并行", "派发", "子任务", "批量", "worker", "spawn", "parallel"), ToolCategory.ACTION);
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
