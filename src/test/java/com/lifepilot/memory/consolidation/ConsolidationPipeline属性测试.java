package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.working.WorkingMemory;
import net.jqwik.api.*;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;

import static org.mockito.Mockito.*;

/**
 * ConsolidationPipeline 属性测试 — 验证 CRON 模式隔离不变量。
 *
 * <p><b>Validates: Requirements 2.5</b></p>
 *
 * @author zsg
 * @since 2026-03-17
 */
class ConsolidationPipeline属性测试 {

    // ─────────────────────────────────────────────
    //  Property P8 — CRON 模式隔离
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.5</b>
     *
     * <p>当 triggerMode=CRON 时，调用 checkIdleConsolidation() 不应触发 consolidate()。
     * 无论空闲时间多长、冷却期是否已过，CRON 模式下空闲检测始终跳过。</p>
     */
    @Property(tries = 100)
    @SuppressWarnings("unchecked")
    void CRON模式下空闲检测不触发巩固(
            @ForAll("空闲阈值") int idleThreshold,
            @ForAll("冷却期") int cooldown) {

        // 构建 CRON 模式配置
        var properties = new MemoryProperties();
        properties.getConsolidation().setTriggerMode("CRON");
        properties.getConsolidation().setIdleThresholdMinutes(idleThreshold);
        properties.getConsolidation().setIdleCooldownMinutes(cooldown);

        // Mock ConsolidationPipeline
        var pipeline = mock(ConsolidationPipeline.class);
        var pipelineProvider = (ObjectProvider<ConsolidationPipeline>) mock(ObjectProvider.class);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);

        // Mock WorkingMemory — 返回很久以前的活动时间（确保空闲条件满足）
        var workingMemory = mock(WorkingMemory.class);
        when(workingMemory.getLastActivityTime()).thenReturn(Instant.EPOCH);
        var wmProvider = (ObjectProvider<WorkingMemory>) mock(ObjectProvider.class);
        when(wmProvider.getIfAvailable()).thenReturn(workingMemory);

        // 创建 MemoryAutoConfiguration 并调用空闲检测
        var config = new MemoryAutoConfiguration(properties, wmProvider, pipelineProvider);
        config.checkIdleConsolidation();

        // 验证：CRON 模式下 consolidate() 不被调用
        verify(pipeline, never()).consolidate();
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成随机空闲阈值（1~120 分钟）。 */
    @Provide
    Arbitrary<Integer> 空闲阈值() {
        return Arbitraries.integers().between(1, 120);
    }

    /** 生成随机冷却期（1~180 分钟）。 */
    @Provide
    Arbitrary<Integer> 冷却期() {
        return Arbitraries.integers().between(1, 180);
    }
}
