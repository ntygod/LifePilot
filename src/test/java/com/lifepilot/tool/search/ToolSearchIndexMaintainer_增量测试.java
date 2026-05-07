package com.lifepilot.tool.search;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.search.cache.SchemaCache;
import com.lifepilot.tool.search.cache.SearchResultCache;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolSearchIndexMaintainer_增量测试 {

    private ToolSearchIndexBuilder builder;
    private DynamicToolRegistry registry;
    private SchemaCache schemaCache;
    private SearchResultCache searchCache;
    private ToolEmbeddingIndex embeddingIndex;
    private ToolSearchIndexMaintainer maintainer;

    @BeforeEach
    void setUp() {
        builder = mock(ToolSearchIndexBuilder.class);
        registry = mock(DynamicToolRegistry.class);
        schemaCache = mock(SchemaCache.class);
        searchCache = mock(SearchResultCache.class);
        embeddingIndex = mock(ToolEmbeddingIndex.class);
        maintainer = new ToolSearchIndexMaintainer(builder, registry, schemaCache, searchCache, embeddingIndex);
    }

    @Test
    void ToolsRegistered事件_对每个工具调用upsert_并清两层缓存() {
        ToolContract t1 = createBuiltinTool("file.read");
        ToolContract t2 = createBuiltinTool("file.write");
        when(registry.resolve("file.read")).thenReturn(Optional.of(t1));
        when(registry.resolve("file.write")).thenReturn(Optional.of(t2));

        maintainer.onToolsRegistered(new ToolRegistryEvent.ToolsRegistered(
                List.of("file.read", "file.write"), ToolLayer.JAVA_NATIVE, "test"));

        verify(builder).upsert(t1);
        verify(builder).upsert(t2);
        verify(schemaCache).invalidate("file.read");
        verify(schemaCache).invalidate("file.write");
        verify(embeddingIndex).refreshOne("file.read", t1.description());
        verify(embeddingIndex).refreshOne("file.write", t2.description());
        verify(searchCache).invalidateAll();
    }

    @Test
    void ToolsRegistered中工具无法resolve_跳过但不抛异常() {
        when(registry.resolve("missing.tool")).thenReturn(Optional.empty());

        maintainer.onToolsRegistered(new ToolRegistryEvent.ToolsRegistered(
                List.of("missing.tool"), ToolLayer.JAVA_NATIVE, "test"));

        verify(builder, never()).upsert(any());
        // Search cache 被 invalidateAll 是因为有 toolId 传入，虽然未找到
        verify(searchCache).invalidateAll();
    }

    @Test
    void ToolsUnregistered事件_对每个工具调用delete_并清两层缓存() {
        maintainer.onToolsUnregistered(new ToolRegistryEvent.ToolsUnregistered(
                List.of("a.b", "c.d"), "test"));

        verify(builder).delete("a.b");
        verify(builder).delete("c.d");
        verify(schemaCache).invalidate("a.b");
        verify(schemaCache).invalidate("c.d");
        verify(embeddingIndex).remove("a.b");
        verify(embeddingIndex).remove("c.d");
        verify(searchCache).invalidateAll();
    }

    @Test
    void ToolConflictDetected事件_仅记录日志_不动索引不动缓存() {
        maintainer.onToolConflict(new ToolRegistryEvent.ToolConflictDetected(
                "conflict.tool", ToolLayer.MCP_EXTERNAL, ToolLayer.JAVA_NATIVE, "JAVA_NATIVE 覆盖"));

        verifyNoInteractions(builder);
        verifyNoInteractions(schemaCache);
        verifyNoInteractions(searchCache);
    }

    @Test
    void 空toolIds的Registered事件_不清缓存不调upsert() {
        maintainer.onToolsRegistered(new ToolRegistryEvent.ToolsRegistered(
                List.of(), ToolLayer.JAVA_NATIVE, "test"));

        verifyNoInteractions(builder);
        verify(searchCache, never()).invalidateAll();
    }

    /**
     * 构造一个最小可用的 BuiltinTool 真实实例（ToolContract 是 sealed 接口不能 mock）。
     */
    private BuiltinTool createBuiltinTool(String id) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("测试工具 " + id)
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT)
                .tags(List.of())
                .executor(input -> ToolResult.success(Map.of("echo", "ok")))
                .build();
    }
}
