package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ContextAssembler 话题切换检测单元测试。
 *
 * @author zsg
 * @since 2026-03-20
 */
@DisplayName("ContextAssembler 话题切换检测")
class ContextAssembler_话题切换测试 {

    /**
     * 相似查询 → 不切换。
     *
     * <p>两次查询的 embedding 余弦相似度高于阈值时，不应判定为话题切换。</p>
     */
    @Test
    void 相似查询不触发话题切换() {
        var llmRouter = mock(LlmRouter.class);
        // 两次 embed 返回相似向量（余弦相似度 = 1.0）
        float[] embedding = {0.5f, 0.5f, 0.5f};
        when(llmRouter.embed(anyString())).thenReturn(embedding);

        var assembler = buildAssembler(llmRouter, buildProperties(0.3f));

        // 第一次调用：初始化 lastQueryEmbedding，返回 false
        boolean first = assembler.detectTopicSwitch("你好");
        assertFalse(first, "首次调用应返回 false（初始化 lastQueryEmbedding）");

        // 第二次调用：相同 embedding，相似度 = 1.0 > 0.3，不切换
        boolean second = assembler.detectTopicSwitch("你好啊");
        assertFalse(second, "相似查询不应触发话题切换");
    }

    /**
     * 差异查询 → 切换 + 缓存清除。
     *
     * <p>两次查询的 embedding 余弦相似度低于阈值时，应判定为话题切换并清除缓存。</p>
     */
    @Test
    void 差异查询触发话题切换并清除缓存() {
        var llmRouter = mock(LlmRouter.class);
        // 第一次 embed 返回向量 A
        float[] embeddingA = {1.0f, 0.0f, 0.0f};
        // 第二次 embed 返回正交向量 B（余弦相似度 = 0.0）
        float[] embeddingB = {0.0f, 1.0f, 0.0f};
        when(llmRouter.embed(anyString()))
                .thenReturn(embeddingA)   // 第一次：初始化
                .thenReturn(embeddingB);  // 第二次：正交向量

        var assembler = buildAssembler(llmRouter, buildProperties(0.3f));

        // 第一次调用：初始化
        assembler.detectTopicSwitch("今天天气怎么样");

        // 第二次调用：正交向量，相似度 = 0.0 < 0.3，应切换
        boolean switched = assembler.detectTopicSwitch("帮我写一段代码");
        assertTrue(switched, "差异查询应触发话题切换");
    }

    /**
     * LlmRouter 为 null → 不检测。
     *
     * <p>LlmRouter 不可用时，话题切换检测应返回 false。</p>
     */
    @Test
    void LlmRouter为null时不检测话题切换() {
        var assembler = buildAssembler(null, buildProperties(0.3f));

        boolean result = assembler.detectTopicSwitch("任意查询");
        assertFalse(result, "LlmRouter 为 null 时应返回 false");
    }

    /**
     * embedding 失败时返回 false。
     *
     * <p>LlmRouter.embed() 抛出异常时，话题切换检测应降级返回 false。</p>
     */
    @Test
    void embedding失败时返回false() {
        var llmRouter = mock(LlmRouter.class);
        float[] initialEmbedding = {0.5f, 0.5f};
        when(llmRouter.embed(anyString()))
                .thenReturn(initialEmbedding)  // 第一次：初始化成功
                .thenThrow(new RuntimeException("embedding 服务不可用"));  // 第二次：失败

        var assembler = buildAssembler(llmRouter, buildProperties(0.3f));

        // 第一次调用：初始化
        assembler.detectTopicSwitch("初始查询");

        // 第二次调用：embed 失败，应降级返回 false
        boolean result = assembler.detectTopicSwitch("新查询");
        assertFalse(result, "embedding 失败时应返回 false");
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

    private MemoryProperties buildProperties(float topicSwitchThreshold) {
        var properties = new MemoryProperties();
        properties.getRetrieval().setTopicSwitchThreshold(topicSwitchThreshold);
        return properties;
    }
}
