package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContextAssembler 批量 embedding 单元测试。
 *
 * @author zsg
 * @since 2026-03-20
 */
@DisplayName("ContextAssembler 批量 embedding")
class ContextAssembler_批量embedding测试 {

    /**
     * embedBatch 正常 → 批量过滤。
     *
     * <p>embedBatch 正常返回时，应使用批量结果进行语义过滤。</p>
     */
    @Test
    void embedBatch正常时批量过滤() {
        var llmRouter = mock(LlmRouter.class);
        // 查询 embedding
        float[] queryEmb = {1.0f, 0.0f, 0.0f};
        when(llmRouter.embed(anyString())).thenReturn(queryEmb);

        // 批量 embedding：第一条与查询相似（余弦 = 1.0），第二条正交（余弦 = 0.0）
        float[][] batchEmb = {
                {1.0f, 0.0f, 0.0f},  // 相似
                {0.0f, 1.0f, 0.0f}   // 不相似
        };
        when(llmRouter.embedBatch(anyList())).thenReturn(batchEmb);

        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "相关内容"),
                buildMessage("msg-2", "不相关内容")
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        assertEquals(1, filtered.size(), "应只保留相似度 ≥ 阈值的消息");
        assertEquals("msg-1", filtered.getFirst().id());
        // 验证使用了 embedBatch 而非逐条 embed
        verify(llmRouter, times(1)).embedBatch(anyList());
    }

    /**
     * embedBatch 异常 → 降级逐条调用。
     *
     * <p>embedBatch 抛出异常时，应降级为逐条 embed() 调用。</p>
     */
    @Test
    void embedBatch异常时降级为逐条调用() {
        var llmRouter = mock(LlmRouter.class);
        float[] queryEmb = {1.0f, 0.0f, 0.0f};
        float[] similarEmb = {0.9f, 0.1f, 0.0f};
        float[] dissimilarEmb = {0.0f, 1.0f, 0.0f};

        // embed: 第一次返回查询 embedding，后续返回消息 embedding
        when(llmRouter.embed(anyString()))
                .thenReturn(queryEmb)      // 查询 embedding
                .thenReturn(similarEmb)    // 第一条消息（降级逐条）
                .thenReturn(dissimilarEmb); // 第二条消息（降级逐条）

        // embedBatch 抛出异常
        when(llmRouter.embedBatch(anyList())).thenThrow(new RuntimeException("批量 embedding 服务不可用"));

        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "相关内容"),
                buildMessage("msg-2", "不相关内容")
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        // 降级后应仍能过滤（第一条相似，第二条不相似）
        assertEquals(1, filtered.size(), "降级后应仍能正常过滤");
        assertEquals("msg-1", filtered.getFirst().id());
    }

    /**
     * 空内容消息被过滤。
     *
     * <p>内容为空或空白的消息应在批量 embedding 前被过滤掉。</p>
     */
    @Test
    void 空内容消息被过滤() {
        var llmRouter = mock(LlmRouter.class);
        float[] queryEmb = {1.0f, 0.0f, 0.0f};
        when(llmRouter.embed(anyString())).thenReturn(queryEmb);

        // 只有一条有效消息
        float[][] batchEmb = {{1.0f, 0.0f, 0.0f}};
        when(llmRouter.embedBatch(anyList())).thenReturn(batchEmb);

        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "有效内容"),
                buildMessage("msg-2", ""),       // 空内容
                buildMessage("msg-3", "   "),    // 空白内容
                buildMessage("msg-4", null)       // null 内容
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        // embedBatch 应只收到 1 条有效内容
        verify(llmRouter).embedBatch(argThat(list -> list.size() == 1));
        assertEquals(1, filtered.size());
        assertEquals("msg-1", filtered.getFirst().id());
    }

    /**
     * 空消息列表返回空列表。
     */
    @Test
    void 空消息列表返回空列表() {
        var llmRouter = mock(LlmRouter.class);
        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var filtered = assembler.filterBySemanticSimilarity(List.of(), "测试查询");

        assertTrue(filtered.isEmpty());
        verify(llmRouter, never()).embed(anyString());
        verify(llmRouter, never()).embedBatch(anyList());
    }

    /**
     * LlmRouter 为 null 时跳过语义过滤，返回原始列表。
     */
    @Test
    void LlmRouter为null时跳过语义过滤() {
        var assembler = buildAssembler(null, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "内容1"),
                buildMessage("msg-2", "内容2")
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        assertEquals(2, filtered.size(), "LlmRouter 为 null 时应返回原始列表");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private ContextAssembler buildAssembler(LlmRouter llmRouter, MemoryProperties memoryProperties) {
        var config = mock(AgentConfigProperties.class);
        var promptRegistry = mock(PromptRegistry.class);
        var hybridRetriever = mock(HybridRetriever.class);
        var workingMemory = mock(WorkingMemory.class);
        var tokenBudgetAllocator = mock(TokenBudgetAllocator.class);
        var strategy = new DefaultMemoryRetrievalStrategy();

        return new ContextAssembler(config, hybridRetriever, workingMemory,
                tokenBudgetAllocator, strategy, null,
                null, null, null, null, null, null, null,
                memoryProperties, llmRouter, null, promptRegistry);
    }

    private MemoryProperties buildProperties(float minCrossSessionSemanticScore) {
        var properties = new MemoryProperties();
        properties.getRetrieval().setMinCrossSessionSemanticScore(minCrossSessionSemanticScore);
        return properties;
    }

    private MessageRecord buildMessage(String id, String content) {
        return new MessageRecord(id, "conv-1", "user", content, null,
                CompressionLevel.ORIGINAL, false, null, 10, Instant.now());
    }
}
