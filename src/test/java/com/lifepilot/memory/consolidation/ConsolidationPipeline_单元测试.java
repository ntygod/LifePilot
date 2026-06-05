package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.agent.learning.consolidation.ConsolidationStats;
import com.lifepilot.agent.learning.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.agent.learning.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConsolidationPipeline 单元测试 — 验证全量巩固的手动语义透传与阶段故障隔离。
 *
 * <p>调度触发逻辑（事件/cron/空闲）已迁移到 ConsolidationScheduler，由
 * {@code ConsolidationScheduler_单元测试} 覆盖；本类只验证管线本身的全量执行行为。</p>
 *
 * @author zsg
 * @since 2026-06-05
 */
@DisplayName("ConsolidationPipeline 全量巩固单元测试")
class ConsolidationPipeline_单元测试 {

    private AgentLearningProperties properties;
    private EpisodicToSemanticConsolidator semanticConsolidator;
    private EpisodicToProceduralConsolidator proceduralConsolidator;

    @BeforeEach
    void setUp() {
        properties = new AgentLearningProperties();
        semanticConsolidator = mock(EpisodicToSemanticConsolidator.class);
        proceduralConsolidator = mock(EpisodicToProceduralConsolidator.class);
        when(semanticConsolidator.consolidate()).thenReturn(
                new ConsolidationStats("SEMANTIC", 0, 0, 0, 0, 0, 0, 0L));
        when(proceduralConsolidator.consolidate()).thenReturn(
                new ConsolidationStats("PROCEDURAL", 0, 0, 0, 0, 0, 0, 0L));
    }

    @Test
    @DisplayName("手动全量巩固应让用户画像绕过防抖")
    void 手动巩固应让用户画像绕过防抖() {
        var userProfileConsolidator = mock(UserProfileConsolidator.class);
        var pipeline = new ConsolidationPipeline(
                semanticConsolidator, proceduralConsolidator, properties,
                null, null, userProfileConsolidator, null, null, null);

        pipeline.consolidate(true);

        verify(userProfileConsolidator).consolidate(true);
    }

    @Test
    @DisplayName("单阶段异常不阻塞后续阶段")
    void 单阶段异常不阻塞后续阶段() {
        when(semanticConsolidator.consolidate()).thenThrow(new RuntimeException("语义巩固故障"));
        var pipeline = new ConsolidationPipeline(
                semanticConsolidator, proceduralConsolidator, properties,
                null, null, null, null, null, null);

        // 语义巩固抛异常，但管线应继续执行程序巩固
        pipeline.consolidate();

        verify(semanticConsolidator).consolidate();
        verify(proceduralConsolidator).consolidate();
    }
}
