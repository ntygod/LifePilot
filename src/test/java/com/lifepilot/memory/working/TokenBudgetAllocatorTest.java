package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenBudgetAllocator 六区域独立预算分配单元测试。
 *
 * @author zsg
 * @since 2026-02-28
 */
class TokenBudgetAllocatorTest {

    private MemoryProperties properties;
    private TokenBudgetAllocator allocator;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        allocator = new TokenBudgetAllocator(properties);
    }

    @Test
    void 默认配置下所有区域预算非负() {
        var result = allocator.allocate(32000, 5, 0.5f);

        assertTrue(result.userProfileBudget() >= 0, "用户画像预算应 >= 0");
        assertTrue(result.currentSessionBudget() >= 0, "当前会话预算应 >= 0");
        assertTrue(result.crossSessionBudget() >= 0, "跨会话预算应 >= 0");
        assertTrue(result.knowledgeEntityBudget() >= 0, "知识实体预算应 >= 0");
        assertTrue(result.proceduralBudget() >= 0, "操作模板预算应 >= 0");
        assertTrue(result.knowledgeBaseBudget() >= 0, "知识库预算应 >= 0");
    }

    @Test
    void 六区域预算之和不超过剩余预算() {
        var result = allocator.allocate(32000, 5, 0.5f);

        int remaining = result.totalBudget() - result.systemPromptBudget() - result.userMessageBudget();
        int sixRegionSum = result.userProfileBudget() + result.currentSessionBudget()
                + result.crossSessionBudget() + result.knowledgeEntityBudget()
                + result.proceduralBudget() + result.knowledgeBaseBudget();

        assertTrue(sixRegionSum <= remaining,
                "六区域总和 %d 应 <= 剩余预算 %d".formatted(sixRegionSum, remaining));
    }

    @Test
    void 每个区域预算不超过配置的max上限() {
        var budget = properties.getTokenBudget();
        var result = allocator.allocate(32000, 5, 0.5f);

        assertTrue(result.userProfileBudget() <= budget.getUserProfileMax(),
                "用户画像 %d 应 <= max %d".formatted(result.userProfileBudget(), budget.getUserProfileMax()));
        assertTrue(result.currentSessionBudget() <= budget.getCurrentSessionMax(),
                "当前会话 %d 应 <= max %d".formatted(result.currentSessionBudget(), budget.getCurrentSessionMax()));
        assertTrue(result.crossSessionBudget() <= budget.getCrossSessionMax(),
                "跨会话 %d 应 <= max %d".formatted(result.crossSessionBudget(), budget.getCrossSessionMax()));
        assertTrue(result.knowledgeEntityBudget() <= budget.getKnowledgeEntityMax(),
                "知识实体 %d 应 <= max %d".formatted(result.knowledgeEntityBudget(), budget.getKnowledgeEntityMax()));
        assertTrue(result.proceduralBudget() <= budget.getProceduralMax(),
                "操作模板 %d 应 <= max %d".formatted(result.proceduralBudget(), budget.getProceduralMax()));
        assertTrue(result.knowledgeBaseBudget() <= budget.getKnowledgeBaseMax(),
                "知识库 %d 应 <= max %d".formatted(result.knowledgeBaseBudget(), budget.getKnowledgeBaseMax()));
    }

    @Test
    void 系统提示词和用户消息按比例分配() {
        var result = allocator.allocate(32000, 5, 0.5f);

        // 默认 systemPromptRatio=0.10, userMessageRatio=0.15
        assertEquals(3200, result.systemPromptBudget(), "系统提示词应为 10%");
        assertEquals(4800, result.userMessageBudget(), "用户消息应为 15%");
    }

    @Test
    void 高检索相关度时知识实体获得更多预算() {
        var defaultResult = allocator.allocate(32000, 5, 0.5f);
        var highRelevanceResult = allocator.allocate(32000, 5, 0.95f);

        // 高相关度场景下，知识实体检索权重更大
        assertTrue(highRelevanceResult.knowledgeEntityBudget() >= defaultResult.knowledgeEntityBudget(),
                "高相关度时知识实体预算应 >= 默认场景");
    }

    @Test
    void 长对话时当前会话获得更多预算() {
        var defaultResult = allocator.allocate(32000, 5, 0.5f);
        var longConvResult = allocator.allocate(32000, 15, 0.5f);

        // 长对话场景下，当前会话权重更大
        assertTrue(longConvResult.currentSessionBudget() >= defaultResult.currentSessionBudget(),
                "长对话时当前会话预算应 >= 默认场景");
    }

    @Test
    void 优先级截断_小窗口时低优先级先被截断() {
        // 使用极小窗口，迫使截断发生
        var budget = properties.getTokenBudget();
        budget.setCurrentSessionMax(2000);
        budget.setUserProfileMax(500);
        budget.setCrossSessionMax(1000);
        budget.setKnowledgeEntityMax(500);
        budget.setProceduralMax(300);
        budget.setKnowledgeBaseMax(500);

        // 窗口 2000，固定区域占 25%=500，剩余仅 1500
        // 六区域 max 总和 = 4800，必然触发截断
        var result = allocator.allocate(2000, 5, 0.5f);

        int remaining = result.totalBudget() - result.systemPromptBudget() - result.userMessageBudget();
        int sixRegionSum = result.userProfileBudget() + result.currentSessionBudget()
                + result.crossSessionBudget() + result.knowledgeEntityBudget()
                + result.proceduralBudget() + result.knowledgeBaseBudget();

        assertTrue(sixRegionSum <= remaining,
                "截断后六区域总和 %d 应 <= 剩余预算 %d".formatted(sixRegionSum, remaining));

        // 当前会话（最高优先级）应保留最多
        assertTrue(result.currentSessionBudget() >= result.knowledgeBaseBudget(),
                "当前会话预算应 >= 知识库预算");
    }

    @Test
    void 优先级截断_知识库最先被截断() {
        // 极小窗口迫使大量截断
        var budget = properties.getTokenBudget();
        budget.setCurrentSessionMax(1000);
        budget.setUserProfileMax(500);
        budget.setCrossSessionMax(500);
        budget.setKnowledgeEntityMax(500);
        budget.setProceduralMax(300);
        budget.setKnowledgeBaseMax(500);

        // 窗口仅 1000，固定区域占 250，剩余 750
        var result = allocator.allocate(1000, 5, 0.5f);

        int remaining = result.totalBudget() - result.systemPromptBudget() - result.userMessageBudget();
        int sixRegionSum = result.userProfileBudget() + result.currentSessionBudget()
                + result.crossSessionBudget() + result.knowledgeEntityBudget()
                + result.proceduralBudget() + result.knowledgeBaseBudget();

        assertTrue(sixRegionSum <= remaining);

        // 如果知识库被完全截断，操作模板也应被截断或为 0
        if (result.knowledgeBaseBudget() == 0) {
            assertTrue(result.proceduralBudget() <= result.currentSessionBudget(),
                    "操作模板预算应 <= 当前会话预算");
        }
    }

    @Test
    void 零窗口大小返回全零预算() {
        var result = allocator.allocate(0, 0, 0.0f);

        assertEquals(0, result.systemPromptBudget());
        assertEquals(0, result.userMessageBudget());
        assertEquals(0, result.userProfileBudget());
        assertEquals(0, result.currentSessionBudget());
        assertEquals(0, result.crossSessionBudget());
        assertEquals(0, result.knowledgeEntityBudget());
        assertEquals(0, result.proceduralBudget());
        assertEquals(0, result.knowledgeBaseBudget());
        assertEquals(0, result.totalBudget());
    }

    @Test
    void BudgetAllocation总和校验不抛异常() {
        // 正常分配不应触发 BudgetAllocation compact constructor 的校验异常
        assertDoesNotThrow(() -> allocator.allocate(32000, 5, 0.5f));
        assertDoesNotThrow(() -> allocator.allocate(8000, 0, 0.0f));
        assertDoesNotThrow(() -> allocator.allocate(128000, 20, 1.0f));
    }
}
