package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;

import static org.mockito.Mockito.*;

/**
 * MemoryAutoConfiguration → ConsolidationPipeline 空闲触发集成测试。
 *
 * <p>验证空闲检测触发巩固管线的端到端流程：
 * <ul>
 *   <li>IDLE 模式下空闲检测触发巩固</li>
 *   <li>冷却期内不重复触发</li>
 *   <li>CRON 模式下空闲检测不触发</li>
 *   <li>ConsolidationPipeline Bean 不存在时跳过</li>
 * </ul>
 * Mock WorkingMemory.getLastActivityTime() 和 ObjectProvider。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@SuppressWarnings("unchecked")
@DisplayName("MemoryAutoConfiguration → ConsolidationPipeline 空闲触发集成测试")
class ConsolidationPipeline_IdleTrigger_集成测试 {

    private MemoryProperties properties;
    private EpisodicToSemanticConsolidator semanticConsolidator;
    private EpisodicToProceduralConsolidator proceduralConsolidator;
    private ConsolidationPipeline pipeline;
    private ObjectProvider<ConsolidationPipeline> pipelineProvider;
    private ObjectProvider<WorkingMemory> wmProvider;
    private WorkingMemory workingMemory;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        semanticConsolidator = mock(EpisodicToSemanticConsolidator.class);
        proceduralConsolidator = mock(EpisodicToProceduralConsolidator.class);

        // Mock 巩固器返回统计
        when(semanticConsolidator.consolidate()).thenReturn(
                new ConsolidationStats("SEMANTIC", 0, 0, 0, 0, 0, 0, 0L));
        when(proceduralConsolidator.consolidate()).thenReturn(
                new ConsolidationStats("PROCEDURAL", 0, 0, 0, 0, 0, 0, 0L));

        pipeline = new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties, null, null, null);

        pipelineProvider = mock(ObjectProvider.class);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);

        workingMemory = mock(WorkingMemory.class);
        wmProvider = mock(ObjectProvider.class);
        when(wmProvider.getIfAvailable()).thenReturn(workingMemory);
    }

    @Test
    void IDLE模式_空闲检测触发巩固管线() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(5);
        properties.getConsolidation().setIdleCooldownMinutes(60);

        // 模拟很久以前的活动时间（空闲条件满足）
        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();

        // 验证巩固管线被触发
        verify(semanticConsolidator).consolidate();
        verify(proceduralConsolidator).consolidate();
    }

    @Test
    void IDLE模式_冷却期内不重复触发() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(1);
        properties.getConsolidation().setIdleCooldownMinutes(60);

        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);

        // 第一次调用：触发巩固
        config.checkIdleConsolidation();
        verify(semanticConsolidator, times(1)).consolidate();
        verify(proceduralConsolidator, times(1)).consolidate();

        // 第二次调用：冷却期内，不应再次触发
        config.checkIdleConsolidation();
        verify(semanticConsolidator, times(1)).consolidate();
        verify(proceduralConsolidator, times(1)).consolidate();
    }

    @Test
    void CRON模式_空闲检测不触发巩固() {
        properties.getConsolidation().setTriggerMode("CRON");
        properties.getConsolidation().setIdleThresholdMinutes(1);

        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();

        // CRON 模式下空闲检测不触发巩固
        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }

    @Test
    void HYBRID模式_空闲检测和Cron均可触发() {
        properties.getConsolidation().setTriggerMode("HYBRID");
        properties.getConsolidation().setIdleThresholdMinutes(5);
        properties.getConsolidation().setIdleCooldownMinutes(60);

        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        // 空闲检测触发
        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();
        verify(semanticConsolidator, times(1)).consolidate();

        // Cron 触发（通过 ConsolidationPipeline.scheduledConsolidate()）
        pipeline.scheduledConsolidate();
        verify(semanticConsolidator, times(2)).consolidate();
        verify(proceduralConsolidator, times(2)).consolidate();
    }

    @Test
    void 空闲检测_未达阈值不触发() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(30);

        // 模拟刚刚活动过
        when(workingMemory.getLastActivityTime()).thenReturn(Instant.now());

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();

        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }

    @Test
    void ConsolidationPipeline_Bean不存在时跳过() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(1);

        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        // Pipeline Provider 返回 null
        var emptyPipelineProvider = (ObjectProvider<ConsolidationPipeline>) mock(ObjectProvider.class);
        when(emptyPipelineProvider.getIfAvailable()).thenReturn(null);

        var config = new MemoryAutoConfiguration(properties, wmProvider, emptyPipelineProvider);
        config.checkIdleConsolidation();

        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }
}
