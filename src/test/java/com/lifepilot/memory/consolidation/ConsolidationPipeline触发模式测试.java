package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.working.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;

import static org.mockito.Mockito.*;

/**
 * ConsolidationPipeline 触发模式单元测试 — 覆盖 IDLE/CRON/HYBRID 模式行为。
 *
 * @author zsg
 * @since 2026-03-17
 */
@SuppressWarnings("unchecked")
class ConsolidationPipeline触发模式测试 {

    private MemoryProperties properties;
    private ConsolidationPipeline pipeline;
    private EpisodicToSemanticConsolidator semanticConsolidator;
    private EpisodicToProceduralConsolidator proceduralConsolidator;
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

        pipeline = new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties, null, null, null, null);

        pipelineProvider = mock(ObjectProvider.class);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);

        workingMemory = mock(WorkingMemory.class);
        wmProvider = mock(ObjectProvider.class);
        when(wmProvider.getIfAvailable()).thenReturn(workingMemory);
    }

    // ─────────────────────────────────────────────
    //  IDLE 模式：Cron 触发被跳过
    // ─────────────────────────────────────────────

    @Test
    void IDLE模式_Cron触发被跳过() {
        properties.getConsolidation().setTriggerMode("IDLE");
        var cp = new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties, null, null, null, null);

        cp.scheduledConsolidate();

        // IDLE 模式下 Cron 触发应跳过，consolidate() 不执行
        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }

    // ─────────────────────────────────────────────
    //  HYBRID 模式：Cron 和空闲检测均可触发
    // ─────────────────────────────────────────────

    @Test
    void HYBRID模式_Cron触发正常执行() {
        properties.getConsolidation().setTriggerMode("HYBRID");
        var cp = new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties, null, null, null, null);

        cp.scheduledConsolidate();

        // HYBRID 模式下 Cron 触发应正常执行
        verify(semanticConsolidator).consolidate();
        verify(proceduralConsolidator).consolidate();
    }

    @Test
    void HYBRID模式_空闲检测触发巩固() {
        properties.getConsolidation().setTriggerMode("HYBRID");
        properties.getConsolidation().setIdleThresholdMinutes(5);
        properties.getConsolidation().setIdleCooldownMinutes(60);

        // 模拟很久以前的活动时间（空闲条件满足）
        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();

        // HYBRID 模式下空闲检测应触发巩固
        verify(semanticConsolidator).consolidate();
        verify(proceduralConsolidator).consolidate();
    }

    // ─────────────────────────────────────────────
    //  CRON 模式：Cron 触发正常，空闲检测跳过
    // ─────────────────────────────────────────────

    @Test
    void CRON模式_Cron触发正常执行() {
        properties.getConsolidation().setTriggerMode("CRON");
        var cp = new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties, null, null, null, null);

        cp.scheduledConsolidate();

        verify(semanticConsolidator).consolidate();
        verify(proceduralConsolidator).consolidate();
    }

    // ─────────────────────────────────────────────
    //  空闲检测：未达阈值不触发
    // ─────────────────────────────────────────────

    @Test
    void 空闲检测_未达阈值不触发() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(30);

        // 模拟刚刚活动过（空闲条件不满足）
        when(workingMemory.getLastActivityTime()).thenReturn(Instant.now());

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();

        // 未达空闲阈值，不应触发巩固
        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }

    // ─────────────────────────────────────────────
    //  空闲检测：冷却期内不重复触发
    // ─────────────────────────────────────────────

    @Test
    void 空闲检测_冷却期内不重复触发() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(1);
        properties.getConsolidation().setIdleCooldownMinutes(60);

        // 模拟很久以前的活动时间
        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);

        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);

        // 第一次调用应触发
        config.checkIdleConsolidation();
        verify(semanticConsolidator, times(1)).consolidate();

        // 第二次调用在冷却期内，不应再次触发
        config.checkIdleConsolidation();
        verify(semanticConsolidator, times(1)).consolidate();
    }

    // ─────────────────────────────────────────────
    //  ConsolidationPipeline Bean 不存在时跳过
    // ─────────────────────────────────────────────

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

        // Pipeline 不存在，不应触发巩固
        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }
}
