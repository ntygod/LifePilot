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
 * ContextAssembler → LlmRouter.embedBatch() 端到端集成测试。
 *
 * <p>验证批量 embedding + 语义过滤的端到端流程：
 * <ul>
 *   <li>embedBatch 正常时使用批量调用而非逐条调用</li>
 *   <li>embedBatch 异常时降级为逐条 embed() 调用</li>
 *   <li>空内容消息在批量计算前被过滤</li>
 * </ul>
 * Mock LlmRouter.embedBatch() 和 LlmRouter.embed()。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@DisplayName("ContextAssembler → LlmRouter.embedBatch() 集成测试")
class ContextAssembler_BatchEmbedding_集成测试 {

    @Test
    void 端到端_embedBatch正常时使用批量调用进行语义过滤() {
        var llmRouter = mock(LlmRouter.class);

        // 查询 embedding
        float[] queryEmb = {1.0f, 0.0f, 0.0f};
        when(llmRouter.embed(anyString())).thenReturn(queryEmb);

        // 批量 embedding：3 条消息，第 1、3 条与查询相似，第 2 条不相似
        float[][] batchEmb = {
                {0.95f, 0.05f, 0.0f},  // 高相似
                {0.0f, 1.0f, 0.0f},    // 不相似
                {0.9f, 0.1f, 0.0f}     // 高相似
        };
        when(llmRouter.embedBatch(anyList())).thenReturn(batchEmb);

        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "相关内容A"),
                buildMessage("msg-2", "不相关内容"),
                buildMessage("msg-3", "相关内容B")
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        // 验证：只保留相似度 ≥ 阈值的消息
        assertEquals(2, filtered.size());
        assertEquals("msg-1", filtered.get(0).id());
        assertEquals("msg-3", filtered.get(1).id());

        // 验证：使用了 embedBatch 批量调用
        verify(llmRouter, times(1)).embedBatch(anyList());
        // embed 只被调用一次（用于查询 embedding）
        verify(llmRouter, times(1)).embed(anyString());
    }

    @Test
    void 端到端_embedBatch异常时降级为逐条embed调用() {
        var llmRouter = mock(LlmRouter.class);

        float[] queryEmb = {1.0f, 0.0f, 0.0f};
        float[] similarEmb = {0.95f, 0.05f, 0.0f};
        float[] dissimilarEmb = {0.0f, 1.0f, 0.0f};

        // embed: 第一次返回查询 embedding，后续返回消息 embedding
        when(llmRouter.embed(anyString()))
                .thenReturn(queryEmb)       // 查询 embedding
                .thenReturn(similarEmb)     // 第一条消息（降级逐条）
                .thenReturn(dissimilarEmb); // 第二条消息（降级逐条）

        // embedBatch 抛出异常
        when(llmRouter.embedBatch(anyList()))
                .thenThrow(new RuntimeException("批量 embedding 服务不可用"));

        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "相关内容"),
                buildMessage("msg-2", "不相关内容")
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        // 降级后仍能正常过滤
        assertEquals(1, filtered.size());
        assertEquals("msg-1", filtered.getFirst().id());

        // 验证：embedBatch 被尝试调用
        verify(llmRouter, times(1)).embedBatch(anyList());
        // 验证：降级后逐条 embed 被调用（查询 1 次 + 消息 2 次 = 3 次）
        verify(llmRouter, times(3)).embed(anyString());
    }

    @Test
    void 端到端_混合内容消息过滤空白后批量embedding() {
        var llmRouter = mock(LlmRouter.class);

        float[] queryEmb = {1.0f, 0.0f, 0.0f};
        when(llmRouter.embed(anyString())).thenReturn(queryEmb);

        // 只有 2 条有效消息（空白消息被过滤）
        float[][] batchEmb = {
                {0.95f, 0.05f, 0.0f},  // 相似
                {0.9f, 0.1f, 0.0f}     // 相似
        };
        when(llmRouter.embedBatch(anyList())).thenReturn(batchEmb);

        var assembler = buildAssembler(llmRouter, buildProperties(0.45f));

        var fragments = List.of(
                buildMessage("msg-1", "有效内容A"),
                buildMessage("msg-2", ""),          // 空内容
                buildMessage("msg-3", "   "),       // 空白内容
                buildMessage("msg-4", "有效内容B"),
                buildMessage("msg-5", null)          // null 内容
        );

        var filtered = assembler.filterBySemanticSimilarity(fragments, "测试查询");

        // 验证：embedBatch 只收到 2 条有效内容
        verify(llmRouter).embedBatch(argThat(list -> list.size() == 2));
        assertEquals(2, filtered.size());
        assertEquals("msg-1", filtered.get(0).id());
        assertEquals("msg-4", filtered.get(1).id());
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
