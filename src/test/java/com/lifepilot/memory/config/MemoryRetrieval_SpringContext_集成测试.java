package com.lifepilot.memory.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.compression.CompressionService;
import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.memory.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.QueryRewriter;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import com.lifepilot.memory.working.SlotEvictionPolicy;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.memory.working.WorkingMemoryWal;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Spring Context 加载测试 — 验证新增/修改 Bean 的注册和构造函数注入。
 *
 * <p>轻量级测试，不使用 {@code @SpringBootTest}，手动模拟 Bean 注册流程，
 * 验证以下关键点：
 * <ul>
 *   <li>QueryRewriter Bean 可正确构造（LlmRouter + MemoryProperties + PromptRegistry）</li>
 *   <li>WorkingMemory 新构造函数注入 {@code @Nullable CompressionService} 成功</li>
 *   <li>默认配置下各组件无 NoSuchBeanDefinitionException</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@DisplayName("Spring Context Bean 注册验证测试")
class MemoryRetrieval_SpringContext_集成测试 {

    private MemoryProperties properties;
    private LlmRouter llmRouter;
    private PromptRegistry promptRegistry;
    private EpisodicMemory episodicMemory;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        llmRouter = mock(LlmRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        episodicMemory = mock(EpisodicMemory.class);
    }

    @Test
    void QueryRewriter_Bean正确注册() {
        // 模拟 MemoryAutoConfiguration 中的 QueryRewriter Bean 注册逻辑
        // @ConditionalOnMissingBean + @ConditionalOnBean(LlmRouter.class)
        var queryRewriter = new QueryRewriter(llmRouter, properties, promptRegistry);

        assertNotNull(queryRewriter, "QueryRewriter 应成功构造");

        // 验证默认配置下 rewrite 模式为 none
        var result = queryRewriter.rewrite("测试查询");
        assertEquals("测试查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
    }

    @Test
    void WorkingMemory_新构造函数注入CompressionService成功() {
        // 模拟 MemoryAutoConfiguration 中的 WorkingMemory Bean 注册
        // 新增 @Nullable CompressionService 参数
        var tokenBudgetAllocator = new TokenBudgetAllocator(properties);
        var slotEvictionPolicy = mock(SlotEvictionPolicy.class);
        var memoryEventRecorder = mock(MemoryEventRecorder.class);
        var wal = mock(WorkingMemoryWal.class);
        var compressionService = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);

        // 带 CompressionService 构造
        var wmWithCompression = new WorkingMemory(
                properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, compressionService);
        assertNotNull(wmWithCompression, "WorkingMemory 带 CompressionService 应成功构造");

        // 不带 CompressionService 构造（@Nullable）
        var wmWithoutCompression = new WorkingMemory(
                properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, null);
        assertNotNull(wmWithoutCompression, "WorkingMemory 不带 CompressionService 应成功构造");
    }

    @Test
    void 默认配置下所有Bean可正确构造() {
        // 验证默认 MemoryProperties 配置下各组件可正确构造

        // 1. TokenBudgetAllocator
        var tokenBudgetAllocator = new TokenBudgetAllocator(properties);
        assertNotNull(tokenBudgetAllocator);

        // 2. QueryRefiner
        var queryRefiner = new com.lifepilot.memory.retrieval.QueryRefiner(properties);
        assertNotNull(queryRefiner);

        // 3. QueryRewriter（依赖 LlmRouter）
        var queryRewriter = new QueryRewriter(llmRouter, properties, promptRegistry);
        assertNotNull(queryRewriter);

        // 4. CompressionService（依赖 LlmRouter + EpisodicMemory）
        var compressionService = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);
        assertNotNull(compressionService);

        // 5. WorkingMemory（依赖 CompressionService @Nullable）
        var slotEvictionPolicy = mock(SlotEvictionPolicy.class);
        var memoryEventRecorder = mock(MemoryEventRecorder.class);
        var wal = mock(WorkingMemoryWal.class);
        var workingMemory = new WorkingMemory(
                properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, compressionService);
        assertNotNull(workingMemory);

        // 6. ConsolidationPipeline（依赖两个巩固器）
        var semanticConsolidator = mock(EpisodicToSemanticConsolidator.class);
        var proceduralConsolidator = mock(EpisodicToProceduralConsolidator.class);
        var pipeline = new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties, null, null, null, null);
        assertNotNull(pipeline);
    }

    @Test
    void MemoryProperties默认配置值正确() {
        // 验证新增配置项的默认值
        var retrieval = properties.getRetrieval();
        assertEquals("none", retrieval.getQueryRewriteMode(), "默认查询改写模式应为 none");
        assertEquals(3, retrieval.getMaxRewrites(), "默认最大改写数应为 3");
        assertEquals(5000, retrieval.getRewriteTimeoutMs(), "默认改写超时应为 5000ms");
        assertEquals(0.15f, retrieval.getMinVectorSimilarity(), "默认向量最低相似度应为 0.15");
        assertEquals(0.3f, retrieval.getTopicSwitchThreshold(), "默认话题切换阈值应为 0.3");
        assertEquals(0.08f, retrieval.getMinFusedScore(), "默认 minFusedScore 应为 0.08");
        assertEquals(0.45f, retrieval.getMinCrossSessionSemanticScore(), "默认跨会话语义阈值应为 0.45");

        var consolidation = properties.getConsolidation();
        assertEquals("CRON", consolidation.getTriggerMode(), "默认触发模式应为 CRON");
        assertEquals(30, consolidation.getIdleThresholdMinutes(), "默认空闲阈值应为 30 分钟");
        assertEquals(60, consolidation.getIdleCooldownMinutes(), "默认冷却期应为 60 分钟");

        var compression = properties.getCompression();
        assertEquals("sliding-window", compression.getStrategy(), "默认压缩策略应为 sliding-window");
        assertEquals(20, compression.getWindowSize(), "默认窗口大小应为 20");
        assertEquals(2, compression.getWindowOverlap(), "默认窗口重叠应为 2");

        var reranker = properties.getReranker();
        assertTrue(reranker.isEnabled(), "默认精排应为启用（强制关闭开关语义）");
        assertEquals(10, reranker.getTopK(), "默认精排 topK 应为 10");
    }
}
