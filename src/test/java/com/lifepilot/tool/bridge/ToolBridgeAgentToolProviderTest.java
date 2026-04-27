package com.lifepilot.tool.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import com.lifepilot.tool.tier1.Tier1Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolBridgeAgentToolProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-26
 */
class ToolBridgeAgentToolProviderTest {

    @Test
    void OpenAI兼容工具名应转换为合法别名并可反查原始ID() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("datastore.query_documents")
                .name("查询集合文档")
                .description("查询集合文档")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());

        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        when(pipeline.execute(anyString(), anyMap(), anyString(), any(), any(), anyMap()))
                .thenReturn(ToolResult.success(Map.of("ok", true)));

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                pipeline,
                new ObjectMapper(),
                30000,
                tier1For("datastore.query_documents")
        );

        var callbacks = provider.getToolCallbacks(baseState(), null);
        String modelToolName = callbacks.getFirst().getToolDefinition().name();

        assertThat(modelToolName).isEqualTo("datastore_query_documents");
        assertThat(provider.resolveCanonicalToolId(modelToolName))
                .isEqualTo("datastore.query_documents");
        assertThat(provider.resolveToolDisplayName(modelToolName)).isEqualTo("查询集合文档");
        assertThat(callbacks.getFirst().call("{}")).contains("\"ok\":true");
    }

    @Test
    void 空输入Schema应归一化为object类型供兼容厂商使用() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("process.list")
                .name("处理列表")
                .description("处理列表")
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                mock(ToolExecutionPipeline.class),
                new ObjectMapper(),
                30000,
                tier1For("process.list")
        );

        var callbacks = provider.getToolCallbacks(baseState(), null);

        assertThat(callbacks.getFirst().getToolDefinition().inputSchema())
                .contains("\"type\":\"object\"")
                .contains("\"properties\":{}");
    }

    @Test
    void 缺少根类型的输入Schema应自动补全为object() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("process.items")
                .name("处理条目")
                .description("处理条目")
                .inputSchema(JsonSchema.of(Map.of(
                        "properties", Map.of(
                                "items", Map.of("type", "array")
                        )
                )))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                mock(ToolExecutionPipeline.class),
                new ObjectMapper(),
                30000,
                tier1For("process.items")
        );

        var callbacks = provider.getToolCallbacks(baseState(), null);

        assertThat(callbacks.getFirst().getToolDefinition().inputSchema())
                .contains("\"type\":\"object\"")
                .contains("\"items\":{\"type\":\"array\"}");
    }

    @Test
    void ResourceSerialized文件工具应生成稳定资源集合() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("file.copy")
                .name("复制文件")
                .description("复制文件")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.pathTrees("source", "destination")
                ))
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                mock(ToolExecutionPipeline.class),
                new ObjectMapper(),
                30000,
                tier1For()
        );

        var hint = provider.resolveSchedulingHint(
                "file.copy",
                "{\"source\":\"D:\\\\WorkSpace\\\\a.txt\",\"destination\":\"D:\\\\WorkSpace\\\\b.txt\"}"
        );

        assertThat(hint.mode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
        assertThat(hint.resourceKeys())
                .containsExactly(
                        "tree:D:/WorkSpace/a.txt",
                        "tree:D:/WorkSpace/b.txt"
                );
    }

    @Test
    void ResourceSerialized工具输入解析失败时应回退串行() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("file.write")
                .name("写入文件")
                .description("写入文件")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.RESOURCE_SERIALIZED,
                        ToolScopeResolvers.paths("path")
                ))
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                mock(ToolExecutionPipeline.class),
                new ObjectMapper(),
                30000,
                tier1For()
        );

        var hint = provider.resolveSchedulingHint("file.write", "{not-json");

        assertThat(hint.mode()).isEqualTo(ToolSchedulingMode.SEQUENTIAL);
        assertThat(hint.resourceKeys()).isEmpty();
    }

    @Test
    void 工具别名冲突时应基于稳定快照生成可复现名称() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("foo_bar")
                .name("下划线工具")
                .description("测试冲突")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("foo.bar")
                .name("点号工具")
                .description("测试冲突")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                mock(ToolExecutionPipeline.class),
                new ObjectMapper(),
                30000,
                tier1For("foo_bar", "foo.bar")
        );

        var callbacks = provider.getToolCallbacks(baseState(), null);
        String collidedName = "foo_bar_" + Integer.toHexString("foo_bar".hashCode()).replace('-', '0');
        var toolNames = callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(toolNames).containsExactly("foo_bar", collidedName);
        assertThat(provider.resolveCanonicalToolId("foo_bar")).isEqualTo("foo.bar");
        assertThat(provider.resolveCanonicalToolId(collidedName)).isEqualTo("foo_bar");
    }

    @Test
    void 白名单只读工具应在同一Trace内生成稳定幂等键() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("web.search")
                .name("Web 搜索")
                .description("搜索网页")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());
        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        when(pipeline.execute(anyString(), anyMap(), anyString(), any(), any(), anyMap()))
                .thenReturn(ToolResult.success(Map.of("ok", true)));

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                pipeline,
                new ObjectMapper(),
                30000,
                tier1For("web.search")
        );
        var callback = provider.getToolCallbacks(baseState(), null).getFirst();

        callback.call("{\"query\":\"budget\"}");
        callback.call("{\"query\":\"budget\"}");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(pipeline, times(2)).execute(eq("web.search"), anyMap(), anyString(), keyCaptor.capture(), isNull(), anyMap());
        assertThat(keyCaptor.getAllValues()).hasSize(2);
        assertThat(keyCaptor.getAllValues().getFirst()).isEqualTo(keyCaptor.getAllValues().get(1));
        assertThat(keyCaptor.getAllValues().getFirst()).startsWith("trace:");
    }

    @Test
    void 非白名单工具即使声明幂等也不应注入幂等键() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("file.read")
                .name("读取文件")
                .description("读取文件")
                .inputSchema(JsonSchema.of(Map.of("type", "object")))
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of("ok", true)))
                .build());
        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        when(pipeline.execute(anyString(), anyMap(), anyString(), any(), any(), anyMap()))
                .thenReturn(ToolResult.success(Map.of("ok", true)));

        var provider = new ToolBridgeAgentToolProvider(
                registry,
                pipeline,
                new ObjectMapper(),
                30000,
                tier1For("file.read")
        );
        var callback = provider.getToolCallbacks(baseState(), null).getFirst();

        callback.call("{\"path\":\"demo.txt\"}");

        verify(pipeline).execute(eq("file.read"), anyMap(), anyString(), isNull(), isNull(), anyMap());
    }

    /** 构建一个 Tier1Service mock，其 getCurrentTier1Ids() 返回指定 ID 集合。 */
    private Tier1Service tier1For(String... toolIds) {
        Tier1Service mock = mock(Tier1Service.class);
        when(mock.getCurrentTier1Ids()).thenReturn(Set.of(toolIds));
        return mock;
    }

    private ReactAgentState baseState() {
        return baseState(null);
    }

    private ReactAgentState baseState(List<String> allowedToolIds) {
        var budget = Budget.builder()
                .maxTokens(4096)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("测试工具别名", "session-1", "web", null, null,
                budget, null, 0, null, allowedToolIds, null, null);
        return ReactAgentState.init(request, budget);
    }
}
