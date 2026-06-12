package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.agent.learning.consolidation.ConsolidationScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * ConsolidationScheduler 单元测试 — 验证巩固阶段按触发类型分流。
 *
 * <p>事件驱动阶段在虚拟线程异步执行，使用 Mockito timeout 校验。</p>
 *
 * @author zsg
 * @since 2026-06-05
 */
@DisplayName("ConsolidationScheduler 调度分流单元测试")
class ConsolidationScheduler_单元测试 {

    @Test
    @DisplayName("对话结束触发语义巩固和程序巩固")
    void 对话结束触发语义和程序巩固() {
        var pipeline = mock(ConsolidationPipeline.class);
        var scheduler = new ConsolidationScheduler(pipeline, Duration.ofMinutes(30));

        scheduler.onConversationCompleted();

        verify(pipeline, timeout(2000)).runSemanticConsolidation();
        verify(pipeline, timeout(2000)).runProceduralConsolidation();
        // 对话结束不触发每日阶段
        verify(pipeline, never()).runPreferenceSync();
    }

    @Test
    @DisplayName("每日阶段触发偏好同步、经验合并、经验提升")
    void 每日阶段触发三个阶段() {
        var pipeline = mock(ConsolidationPipeline.class);
        var scheduler = new ConsolidationScheduler(pipeline, Duration.ofMinutes(30));

        scheduler.runDailyStages();

        verify(pipeline).runPreferenceSync();
        verify(pipeline).runExperienceMerge();
        verify(pipeline).runExperiencePromotion();
        verify(pipeline, never()).runSemanticConsolidation();
        verify(pipeline, never()).runRemAssociation();
    }

    @Test
    @DisplayName("空闲阶段触发 REM 联想")
    void 空闲阶段触发REM() {
        var pipeline = mock(ConsolidationPipeline.class);
        var scheduler = new ConsolidationScheduler(pipeline, Duration.ofMinutes(30));

        scheduler.runIdleStages();

        verify(pipeline).runRemAssociation();
    }

    @Test
    @DisplayName("无对话事件时画像防抖不触发")
    void 无事件时画像防抖不触发() {
        var pipeline = mock(ConsolidationPipeline.class);
        var scheduler = new ConsolidationScheduler(pipeline, Duration.ofMinutes(30));

        scheduler.checkProfileDebounce();

        verify(pipeline, never()).runUserProfileConsolidation(anyBoolean());
    }

    @Test
    @DisplayName("防抖窗口未满足时画像不触发")
    void 防抖窗口未满足不触发画像() {
        var pipeline = mock(ConsolidationPipeline.class);
        var scheduler = new ConsolidationScheduler(pipeline, Duration.ofMinutes(30));

        scheduler.onConversationCompleted();   // 登记事件时间 = now
        scheduler.checkProfileDebounce();       // 距事件 < 30min

        verify(pipeline, never()).runUserProfileConsolidation(anyBoolean());
    }

    @Test
    @DisplayName("防抖窗口满足后触发画像巩固")
    void 防抖窗口满足触发画像() {
        var pipeline = mock(ConsolidationPipeline.class);
        // 零防抖窗口：事件登记后立即满足
        var scheduler = new ConsolidationScheduler(pipeline, Duration.ZERO);

        scheduler.onConversationCompleted();
        scheduler.checkProfileDebounce();

        verify(pipeline).runUserProfileConsolidation(false);
    }
}
