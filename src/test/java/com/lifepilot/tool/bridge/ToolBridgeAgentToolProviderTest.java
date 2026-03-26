package com.lifepilot.tool.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.meta.config.MetaProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ToolBridgeAgentToolProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-26
 */
class ToolBridgeAgentToolProviderTest {

    @Test
    void ResourceSerialized文件工具应生成稳定资源集合() {
        DynamicToolRegistry registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));
        registry.registerBuiltinTool(BuiltinTool.builder()
                .id("builtin.file.copy")
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
                new MetaProperties()
        );

        var hint = provider.resolveSchedulingHint(
                "builtin.file.copy",
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
                .id("builtin.file.write")
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
                new MetaProperties()
        );

        var hint = provider.resolveSchedulingHint("builtin.file.write", "{not-json");

        assertThat(hint.mode()).isEqualTo(ToolSchedulingMode.SEQUENTIAL);
        assertThat(hint.resourceKeys()).isEmpty();
    }
}
