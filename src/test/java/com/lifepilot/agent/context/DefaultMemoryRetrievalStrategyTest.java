package com.lifepilot.agent.context;

import com.lifepilot.agent.model.AgentPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultMemoryRetrievalStrategy 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class DefaultMemoryRetrievalStrategyTest {

    private DefaultMemoryRetrievalStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DefaultMemoryRetrievalStrategy();
    }

    @Test
    void UNDERSTANDING阶段_topK为10_向量权重最高() {
        var config = strategy.getStrategy(AgentPhase.UNDERSTANDING);

        assertEquals(10, config.topK());
        assertTrue(config.graphEnabled());
        assertFalse(config.skip());
        // 向量权重 0.50 应为三路中最高
        var w = config.weights();
        assertTrue(w.vectorWeight() > w.ftsWeight());
        assertTrue(w.vectorWeight() > w.graphWeight());
    }

    @Test
    void PLANNING阶段_topK为5_图遍历权重最高() {
        var config = strategy.getStrategy(AgentPhase.PLANNING);

        assertEquals(5, config.topK());
        assertTrue(config.graphEnabled());
        assertFalse(config.skip());
        // 图遍历权重 0.40 应为三路中最高
        var w = config.weights();
        assertTrue(w.graphWeight() > w.vectorWeight());
        assertTrue(w.graphWeight() > w.ftsWeight());
    }

    @Test
    void EXECUTING阶段_topK为3() {
        var config = strategy.getStrategy(AgentPhase.EXECUTING);

        assertEquals(3, config.topK());
        assertFalse(config.graphEnabled());
        assertFalse(config.skip());
    }

    @Test
    void REFLECTING阶段_topK为8_FTS权重最高() {
        var config = strategy.getStrategy(AgentPhase.REFLECTING);

        assertEquals(8, config.topK());
        assertTrue(config.graphEnabled());
        assertFalse(config.skip());
        // FTS 权重 0.45 应为三路中最高
        var w = config.weights();
        assertTrue(w.ftsWeight() > w.vectorWeight());
        assertTrue(w.ftsWeight() > w.graphWeight());
    }

    @Test
    void RESPONDING阶段_topK为5_权重平衡() {
        var config = strategy.getStrategy(AgentPhase.RESPONDING);

        assertEquals(5, config.topK());
        assertTrue(config.graphEnabled());
        assertFalse(config.skip());
        // 向量和 FTS 权重相等（0.35），图遍历略低（0.30）
        var w = config.weights();
        assertEquals(w.vectorWeight(), w.ftsWeight(), 0.001f);
    }

    @Test
    void TERMINATED阶段_跳过检索() {
        var config = strategy.getStrategy(AgentPhase.TERMINATED);

        assertTrue(config.skip());
        assertEquals(0, config.topK());
        assertFalse(config.graphEnabled());
    }
}
