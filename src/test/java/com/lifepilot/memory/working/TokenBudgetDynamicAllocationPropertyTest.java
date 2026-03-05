package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 动态预算分配属性测试 — 验证 {@code hasMemoryData} 参数对预算分配的影响。
 *
 * <p>覆盖两个属性：
 * <ul>
 *   <li>Property 9：{@code hasMemoryData=false} 时记忆区域预算为 0，释放预算重新分配</li>
 *   <li>Property 10：{@code hasMemoryData=true} 时预算分配与修复前一致</li>
 * </ul></p>
 *
 * <p><b>Validates: Property 9, 10, Requirements 2.10, 3.4</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class TokenBudgetDynamicAllocationPropertyTest {

    // ─────────────────────────────────────────────
    //  Property 9 — hasMemoryData=false 时记忆区域预算为 0
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.10</b>
     *
     * <p>对任意上下文窗口大小，当 {@code hasMemoryData=false} 时，
     * 记忆相关区域（crossSession、knowledgeEntity、procedural、knowledgeBase、userProfile）
     * 预算应全部为 0。</p>
     */
    @Property(tries = 200)
    void 无记忆数据时记忆区域预算全部为零(
            @ForAll("contextWindowSizes") int contextWindowSize,
            @ForAll("conversationTurns") int conversationTurns,
            @ForAll("retrievalScores") float topRetrievalScore) {

        var allocator = buildAllocator();
        BudgetAllocation allocation = allocator.allocate(
                contextWindowSize, conversationTurns, topRetrievalScore, false);

        assertEquals(0, allocation.crossSessionBudget(),
                "hasMemoryData=false 时 crossSessionBudget 应为 0，窗口=" + contextWindowSize);
        assertEquals(0, allocation.knowledgeEntityBudget(),
                "hasMemoryData=false 时 knowledgeEntityBudget 应为 0，窗口=" + contextWindowSize);
        assertEquals(0, allocation.proceduralBudget(),
                "hasMemoryData=false 时 proceduralBudget 应为 0，窗口=" + contextWindowSize);
        assertEquals(0, allocation.knowledgeBaseBudget(),
                "hasMemoryData=false 时 knowledgeBaseBudget 应为 0，窗口=" + contextWindowSize);
        assertEquals(0, allocation.userProfileBudget(),
                "hasMemoryData=false 时 userProfileBudget 应为 0，窗口=" + contextWindowSize);
    }

    /**
     * <b>Validates: Requirements 2.10</b>
     *
     * <p>对任意上下文窗口大小，当 {@code hasMemoryData=false} 时，
     * 释放的记忆预算应重新分配给 userMessage 和 currentSession，
     * 使得总分配不超过总预算。</p>
     */
    @Property(tries = 200)
    void 无记忆数据时释放预算重新分配给用户消息和当前会话(
            @ForAll("contextWindowSizes") int contextWindowSize,
            @ForAll("conversationTurns") int conversationTurns,
            @ForAll("retrievalScores") float topRetrievalScore) {

        var allocator = buildAllocator();
        BudgetAllocation allocation = allocator.allocate(
                contextWindowSize, conversationTurns, topRetrievalScore, false);

        // 总分配不超过总预算（BudgetAllocation 紧凑构造器已校验，此处双重确认）
        int sum = allocation.userProfileBudget() + allocation.currentSessionBudget()
                + allocation.crossSessionBudget() + allocation.knowledgeEntityBudget()
                + allocation.proceduralBudget() + allocation.knowledgeBaseBudget()
                + allocation.systemPromptBudget() + allocation.userMessageBudget();
        assertTrue(sum <= allocation.totalBudget(),
                "所有区域预算之和 " + sum + " 不应超过总预算 " + allocation.totalBudget());

        // 无记忆数据时，userMessage + currentSession + systemPrompt 应占据全部预算
        int nonMemorySum = allocation.systemPromptBudget() + allocation.userMessageBudget()
                + allocation.currentSessionBudget();
        assertEquals(sum, nonMemorySum,
                "无记忆数据时，仅 systemPrompt + userMessage + currentSession 应有预算");

        // currentSession 或 userMessage 应获得释放的预算（比有数据时更大）
        if (contextWindowSize > 0) {
            int expectedBaseUserMessage = Math.round(contextWindowSize * 0.15f);
            // 释放预算后 userMessage 应 >= 基础值，currentSession 应 >= 0
            assertTrue(allocation.userMessageBudget() >= expectedBaseUserMessage,
                    "无记忆数据时 userMessage 应 >= 基础值 " + expectedBaseUserMessage
                            + "，实际: " + allocation.userMessageBudget());
            assertTrue(allocation.currentSessionBudget() >= 0,
                    "无记忆数据时 currentSession 应 >= 0");
        }
    }

    // ─────────────────────────────────────────────
    //  Property 10 — hasMemoryData=true 时预算分配与修复前一致
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 3.4</b>
     *
     * <p>对任意上下文窗口大小，当 {@code hasMemoryData=true} 时，
     * 预算分配应满足修复前的所有约束：
     * <ul>
     *   <li>系统提示词 = contextWindowSize * systemPromptRatio</li>
     *   <li>用户消息 = contextWindowSize * userMessageRatio</li>
     *   <li>六区域各自不超过 max 上限</li>
     *   <li>六区域总和不超过剩余预算</li>
     *   <li>所有区域非负</li>
     * </ul></p>
     */
    @Property(tries = 200)
    void 有记忆数据时预算分配满足原有约束(
            @ForAll("contextWindowSizes") int contextWindowSize,
            @ForAll("conversationTurns") int conversationTurns,
            @ForAll("retrievalScores") float topRetrievalScore) {

        var properties = new MemoryProperties();
        var budget = properties.getTokenBudget();
        var allocator = new TokenBudgetAllocator(properties);
        BudgetAllocation allocation = allocator.allocate(
                contextWindowSize, conversationTurns, topRetrievalScore, true);

        // 系统提示词和用户消息按比例分配
        int expectedSystemPrompt = Math.round(contextWindowSize * budget.getSystemPromptRatio());
        int expectedUserMessage = Math.round(contextWindowSize * budget.getUserMessageRatio());
        assertEquals(expectedSystemPrompt, allocation.systemPromptBudget(),
                "系统提示词应为 contextWindowSize * systemPromptRatio");
        assertEquals(expectedUserMessage, allocation.userMessageBudget(),
                "用户消息应为 contextWindowSize * userMessageRatio");

        // 所有区域非负
        assertTrue(allocation.userProfileBudget() >= 0, "用户画像预算不应为负");
        assertTrue(allocation.currentSessionBudget() >= 0, "当前会话预算不应为负");
        assertTrue(allocation.crossSessionBudget() >= 0, "跨会话预算不应为负");
        assertTrue(allocation.knowledgeEntityBudget() >= 0, "知识实体预算不应为负");
        assertTrue(allocation.proceduralBudget() >= 0, "操作模板预算不应为负");
        assertTrue(allocation.knowledgeBaseBudget() >= 0, "知识库预算不应为负");

        // 六区域各自不超过 max 上限
        assertTrue(allocation.userProfileBudget() <= budget.getUserProfileMax(),
                "用户画像 " + allocation.userProfileBudget() + " 应 <= max " + budget.getUserProfileMax());
        assertTrue(allocation.currentSessionBudget() <= budget.getCurrentSessionMax(),
                "当前会话 " + allocation.currentSessionBudget() + " 应 <= max " + budget.getCurrentSessionMax());
        assertTrue(allocation.crossSessionBudget() <= budget.getCrossSessionMax(),
                "跨会话 " + allocation.crossSessionBudget() + " 应 <= max " + budget.getCrossSessionMax());
        assertTrue(allocation.knowledgeEntityBudget() <= budget.getKnowledgeEntityMax(),
                "知识实体 " + allocation.knowledgeEntityBudget() + " 应 <= max " + budget.getKnowledgeEntityMax());
        assertTrue(allocation.proceduralBudget() <= budget.getProceduralMax(),
                "操作模板 " + allocation.proceduralBudget() + " 应 <= max " + budget.getProceduralMax());
        assertTrue(allocation.knowledgeBaseBudget() <= budget.getKnowledgeBaseMax(),
                "知识库 " + allocation.knowledgeBaseBudget() + " 应 <= max " + budget.getKnowledgeBaseMax());

        // 六区域总和不超过剩余预算
        int remaining = Math.max(0, contextWindowSize - allocation.systemPromptBudget() - allocation.userMessageBudget());
        int sixRegionSum = allocation.userProfileBudget() + allocation.currentSessionBudget()
                + allocation.crossSessionBudget() + allocation.knowledgeEntityBudget()
                + allocation.proceduralBudget() + allocation.knowledgeBaseBudget();
        assertTrue(sixRegionSum <= remaining,
                "六区域总和 " + sixRegionSum + " 应 <= 剩余预算 " + remaining);

        // 总预算等于上下文窗口大小
        assertEquals(contextWindowSize, allocation.totalBudget(),
                "总预算应等于上下文窗口大小");
    }

    /**
     * <b>Validates: Requirements 3.4</b>
     *
     * <p>对任意上下文窗口大小，当 {@code hasMemoryData=true} 时，
     * 记忆区域应有非零预算（窗口足够大时）。</p>
     */
    @Property(tries = 100)
    void 有记忆数据且窗口足够大时记忆区域有非零预算(
            @ForAll("largeContextWindowSizes") int contextWindowSize,
            @ForAll("conversationTurns") int conversationTurns,
            @ForAll("retrievalScores") float topRetrievalScore) {

        var allocator = buildAllocator();
        BudgetAllocation allocation = allocator.allocate(
                contextWindowSize, conversationTurns, topRetrievalScore, true);

        // 窗口足够大时（>= 8000），至少有一个记忆区域预算 > 0
        int memorySum = allocation.crossSessionBudget() + allocation.knowledgeEntityBudget()
                + allocation.proceduralBudget() + allocation.knowledgeBaseBudget()
                + allocation.userProfileBudget();
        assertTrue(memorySum > 0,
                "hasMemoryData=true 且窗口=" + contextWindowSize + " 时，记忆区域总预算应 > 0，实际: " + memorySum);
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成上下文窗口大小：4000 ~ 128000。 */
    @Provide
    Arbitrary<Integer> contextWindowSizes() {
        return Arbitraries.integers().between(4000, 128000);
    }

    /** 生成较大的上下文窗口大小：8000 ~ 128000（确保记忆区域有预算空间）。 */
    @Provide
    Arbitrary<Integer> largeContextWindowSizes() {
        return Arbitraries.integers().between(8000, 128000);
    }

    /** 生成对话轮次：0 ~ 30。 */
    @Provide
    Arbitrary<Integer> conversationTurns() {
        return Arbitraries.integers().between(0, 30);
    }

    /** 生成检索相关度评分：0.0 ~ 1.0。 */
    @Provide
    Arbitrary<Float> retrievalScores() {
        return Arbitraries.floats().between(0.0f, 1.0f);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 使用默认配置构建 TokenBudgetAllocator。 */
    private TokenBudgetAllocator buildAllocator() {
        return new TokenBudgetAllocator(new MemoryProperties());
    }
}
