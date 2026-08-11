package com.lifepilot.tool.search;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工具语义索引单元测试。
 *
 * @author zsg
 * @since 2026-07-05
 */
class ToolEmbeddingIndex_单元测试 {

    @Test
    void 冷索引增量刷新_不主动调用向量服务() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        ToolEmbeddingIndex index = new ToolEmbeddingIndex(registry, embeddingRouter);

        index.refreshOne("file.read", "读取本地文件");

        assertThat(index.size()).isZero();
        verifyNoInteractions(embeddingRouter);
    }

    @Test
    void 首次语义搜索_懒构建索引后返回命中() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        ToolEmbeddingIndex index = new ToolEmbeddingIndex(registry, embeddingRouter);
        when(registry.getAllTools()).thenReturn(List.of(
                createBuiltinTool("file.read", "读取本地文件"),
                createBuiltinTool("file.write", "写入本地文件")
        ));
        when(embeddingRouter.embedBatch(
                eq(List.of("读取本地文件", "写入本地文件")),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        )).thenReturn(new float[][]{
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        });
        when(embeddingRouter.embed(
                eq("写入"),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        )).thenReturn(new float[]{0.0f, 1.0f});

        List<ToolEmbeddingIndex.SemanticHit> hits = index.search("写入", null, 2);

        assertThat(hits)
                .extracting(ToolEmbeddingIndex.SemanticHit::toolId)
                .containsExactly("file.write", "file.read");
        verify(embeddingRouter).embedBatch(
                eq(List.of("读取本地文件", "写入本地文件")),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        );
        verify(embeddingRouter).embed(
                eq("写入"),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        );
    }

    @Test
    void 向量服务失败后_冷却期内跳过后续搜索() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);
        EmbeddingRouter embeddingRouter = mock(EmbeddingRouter.class);
        ToolEmbeddingIndex index = new ToolEmbeddingIndex(registry, embeddingRouter);
        when(registry.getAllTools()).thenReturn(List.of(createBuiltinTool("file.read", "读取本地文件")));
        when(embeddingRouter.embedBatch(
                anyList(),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        )).thenThrow(new RuntimeException("向量服务不可用"));

        assertThat(index.search("读取", null, 3)).isEmpty();
        assertThat(index.search("读取", null, 3)).isEmpty();

        verify(embeddingRouter, times(1)).embedBatch(
                anyList(),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        );
        verify(embeddingRouter, never()).embed(
                eq("读取"),
                eq(EmbeddingUseCase.DEFAULT),
                isNull(),
                isNull()
        );
    }

    private static BuiltinTool createBuiltinTool(String id, String description) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description(description)
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT)
                .tags(List.of())
                .executor(_ -> ToolResult.success(Map.of("ok", true)))
                .build();
    }
}
