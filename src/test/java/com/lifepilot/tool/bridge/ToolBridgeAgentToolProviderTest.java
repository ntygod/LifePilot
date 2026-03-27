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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
                30000
        );

        var callbacks = provider.getToolCallbacks(baseState(), null);
        String modelToolName = callbacks.getFirst().getToolDefinition().name();

        assertThat(modelToolName).isEqualTo("datastore_query_documents");
        assertThat(provider.resolveCanonicalToolId(modelToolName))
                .isEqualTo("datastore.query_documents");
        assertThat(provider.resolveToolDisplayName(modelToolName)).isEqualTo("查询集合文档");
        assertThat(callbacks.getFirst().call("{}")).contains("\"ok\":true");
    }

    private ReactAgentState baseState() {
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
                budget, null, 0, null, null, null, null);
        return ReactAgentState.init(request, budget);
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
                30000
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
                30000
        );

        var hint = provider.resolveSchedulingHint("file.write", "{not-json");

        assertThat(hint.mode()).isEqualTo(ToolSchedulingMode.SEQUENTIAL);
        assertThat(hint.resourceKeys()).isEmpty();
    }
}
