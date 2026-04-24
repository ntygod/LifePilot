package com.lifepilot.integration;

import com.lifepilot.LifePilotApplication;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.bridge.ToolBridgeAgentToolProvider;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.search.ToolDescribeResult;
import com.lifepilot.tool.search.ToolDescribeService;
import com.lifepilot.tool.search.ToolListResult;
import com.lifepilot.tool.search.ToolListService;
import com.lifepilot.tool.search.ToolSearchConfidence;
import com.lifepilot.tool.search.ToolSearchHit;
import com.lifepilot.tool.search.ToolSearchResult;
import com.lifepilot.tool.search.ToolSearchService;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具暴露机制重构 E2E 集成测试。
 *
 * <p>启动 Spring 上下文（最小化依赖：tool / memory / knowledge / datastore / skills），
 * 通过 {@link TestToolConfiguration} 注册典型 Tier 1 / Tier 2 BuiltinTool bean 供测试使用；
 * 验证 search → describe → ToolCallback 全链路 + Tier 1 / Meta / activated / allowed 四种
 * 可见场景。</p>
 *
 * <p>不开 meta / workflow / media / llm / multi-agent：这些模块的硬依赖链过长
 * （CapabilityAggregator → WorkflowRegistry；WorkflowEngine → MultimodalRouter → GenerationRouter…），
 * 引入它们会导致集成测试脆弱且启动耗时。本测试聚焦工具暴露机制本身的装配与行为，
 * 真实生产工具的召回率在 {@code ToolSearchQuality_召回率回归测试} 中单独回归。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@SpringBootTest(
        classes = LifePilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "lifepilot.tool.enabled=true",
                "lifepilot.meta.enabled=false",
                "lifepilot.memory.enabled=true",
                "lifepilot.knowledge.enabled=true",
                "lifepilot.datastore.enabled=true",
                "lifepilot.workflow.enabled=false",
                "lifepilot.skills.enabled=true",
                "lifepilot.skills.auto-generation.enabled=false",
                "lifepilot.llm.enabled=false",
                "lifepilot.agent.enabled=false",
                "lifepilot.agent.multi-agent.enabled=false",
                "lifepilot.gateway.enabled=false",
                "lifepilot.media.enabled=false",
                "lifepilot.a2a.enabled=false",
                "lifepilot.mcp.enabled=false",
                "lifepilot.marketplace.enabled=false",
                "lifepilot.notification.enabled=false"
        }
)
@Import(ToolExposureRefactor_端到端集成测试.TestToolConfiguration.class)
@ActiveProfiles("test")
class ToolExposureRefactor_端到端集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-tool-e2e-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-tool-e2e-vec-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired ToolSearchService searchService;
    @Autowired ToolDescribeService describeService;
    @Autowired ToolListService listService;
    @Autowired ToolBridgeAgentToolProvider bridgeProvider;

    @Test
    void 完整链路_搜索到描述到获取ToolCallback() {
        ReactAgentState state = sampleState(Set.of(), List.of());

        ToolSearchResult sr = searchService.search(state, "delete file", null, 5);
        assertThat(sr.results()).isNotEmpty();

        // top 结果应当是 Tier 2 工具（非 Tier 1、非 meta）
        ToolSearchHit top = sr.results().get(0);
        assertThat(top.id()).isNotIn("tools.search", "tools.describe", "tools.list");

        // describe 取得完整 schema
        ToolDescribeResult dr = describeService.describe(List.of(top.id()));
        assertThat(dr.schemas()).containsKey(top.id());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) dr.schemas().get(top.id());
        assertThat(schema).containsKeys(
                "description", "input_schema", "output_schema", "risk_level", "idempotent");
    }

    @Test
    void ToolBridge_暴露Tier1和Meta工具() {
        ReactAgentState state = sampleState(Set.of(), List.of());
        List<ToolCallback> callbacks = bridgeProvider.getToolCallbacks(state, null);
        List<String> visible = callbacks.stream()
                .map(c -> c.getToolDefinition().name())
                .toList();

        // Tier 1 pinned + Meta 工具必须可见（模型工具名经过 sanitize，'.' → '_'）
        assertThat(visible).anyMatch(n -> n.equals("tools_search") || n.equals("tools.search"));
        assertThat(visible).anyMatch(n -> n.contains("file_read") || n.contains("file.read"));
    }

    @Test
    void 零结果_返回NONE置信度并带hint() {
        ReactAgentState state = sampleState(Set.of(), List.of());
        ToolSearchResult r = searchService.search(state, "zzzzzyxyzqqqnotatool", null, 5);
        assertThat(r.confidence()).isEqualTo(ToolSearchConfidence.NONE);
        assertThat(r.hint()).contains("Try broader keywords");
    }

    @Test
    void tools_list_按category过滤返回ID分组() {
        ToolListResult r = listService.list("ACTION");
        assertThat(r.categories()).containsOnlyKeys("ACTION");
        assertThat(r.total()).isGreaterThan(0);
    }

    @Test
    void Skill激活场景_activatedToolIds并入可见集合() {
        // datastore 属于 Tier 2，放到 activatedToolIds 后应该可见
        ReactAgentState state = sampleState(Set.of("datastore"), List.of());
        List<ToolCallback> callbacks = bridgeProvider.getToolCallbacks(state, null);
        List<String> visible = callbacks.stream()
                .map(c -> c.getToolDefinition().name())
                .toList();

        assertThat(visible).anyMatch(n -> n.contains("datastore"));
    }

    @Test
    void allowedToolIds非空_受限白名单过滤生效() {
        ReactAgentState state = sampleState(Set.of(), List.of("file.read"));
        List<ToolCallback> callbacks = bridgeProvider.getToolCallbacks(state, null);
        List<String> visible = callbacks.stream()
                .map(c -> c.getToolDefinition().name())
                .toList();

        // 白名单内的 file.read 必须可见
        assertThat(visible).anyMatch(n -> n.contains("file_read") || n.contains("file.read"));
    }

    @Test
    void describe_不存在的id_返回notFound() {
        ToolDescribeResult r = describeService.describe(List.of("does.not.exist"));
        assertThat(r.schemas()).isEmpty();
        assertThat(r.notFound()).containsExactly("does.not.exist");
        assertThat(r.suggestion()).isNotBlank();
    }

    @Test
    void describe_批量_部分成功部分未找到() {
        // 通过搜索拿到一个真实存在的 Tier 2 工具 ID
        ReactAgentState state = sampleState(Set.of(), List.of());
        ToolSearchResult sr = searchService.search(state, "delete file", null, 1);
        assertThat(sr.results()).isNotEmpty();
        String realId = sr.results().get(0).id();

        ToolDescribeResult r = describeService.describe(List.of(realId, "fake.tool"));
        assertThat(r.schemas()).containsKey(realId);
        assertThat(r.notFound()).containsExactly("fake.tool");
    }

    /**
     * 构造最小 ReactAgentState mock。
     *
     * <p>覆盖 ToolBridgeAgentToolProvider.getToolCallbacks() 调用到的全部 getter，
     * 避免 {@code sourceKind().name()} 等链式调用触发 NPE。</p>
     */
    private ReactAgentState sampleState(Set<String> activated, List<String> allowed) {
        ReactAgentState state = mock(ReactAgentState.class);
        when(state.traceId()).thenReturn("e2e-test");
        when(state.sessionId()).thenReturn("e2e-session");
        when(state.turnId()).thenReturn("e2e-turn");
        when(state.userId()).thenReturn("e2e-user");
        when(state.channel()).thenReturn("web-test");
        when(state.sourceKind()).thenReturn(SourceKind.SYSTEM);
        when(state.channelPlatform()).thenReturn(null);
        when(state.channelInstanceId()).thenReturn(null);
        when(state.depth()).thenReturn(0);
        when(state.budget()).thenReturn(null);
        when(state.activatedToolIds()).thenReturn(activated);
        when(state.allowedToolIds()).thenReturn(allowed);
        return state;
    }

    /**
     * 测试专用 Tier 1 / Tier 2 BuiltinTool bean。
     *
     * <p>meta 模块关闭后生产 ToolProvider 都不装配，本 TestConfiguration 提供
     * 一组典型工具 stub（镜像真实 tags / description）供 E2E 用例验证。</p>
     */
    @TestConfiguration
    static class TestToolConfiguration {

        @Bean
        BuiltinTool fileReadStub() {
            return stub("file.read", "文件读取",
                    "Read a file content from the given path; supports text and binary files.",
                    List.of("infrastructure", "read", "file", "load", "content"),
                    ToolCategory.PERCEPTION);
        }

        @Bean
        BuiltinTool fileWriteStub() {
            return stub("file.write", "文件写入",
                    "Create a new file or overwrite/append content to an existing path.",
                    List.of("infrastructure", "write", "file", "save", "create", "append"),
                    ToolCategory.ACTION);
        }

        @Bean
        BuiltinTool fileManageStub() {
            return stub("file.manage", "文件管理",
                    "Move, copy, delete, or create files and directories with batch operations.",
                    List.of("infrastructure", "manage", "move", "copy", "delete", "file", "directory"),
                    ToolCategory.ACTION);
        }

        @Bean
        BuiltinTool datastoreStub() {
            return stub("datastore", "数据存储",
                    "Query datastore collections with find, aggregate, and update operations.",
                    List.of("datastore", "storage", "database", "collection", "query"),
                    ToolCategory.STORAGE);
        }

        @Bean
        BuiltinTool shellExecStub() {
            return stub("shell.exec", "命令执行",
                    "Execute a shell command synchronously and return stdout and stderr content.",
                    List.of("infrastructure", "shell", "exec", "command", "bash"),
                    ToolCategory.ACTION);
        }

        private static BuiltinTool stub(String id, String name, String description,
                                        List<String> tags, ToolCategory category) {
            return BuiltinTool.builder()
                    .id(id)
                    .name(name)
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
                    .build();
        }
    }
}
