package com.lifepilot.skill.activation;

import com.lifepilot.skill.model.SubAgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link SkillMetricsTracker} 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class SkillMetricsTrackerTest {

    private SkillMetricsTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SkillMetricsTracker();
    }

    @Test
    void getMetrics_无记录时返回empty() {
        Optional<SkillMetricsTracker.SkillMetrics> result = tracker.getMetrics("unknown");
        assertThat(result).isEmpty();
    }

    @Test
    void getSuccessRate_无记录时返回零() {
        assertThat(tracker.getSuccessRate("unknown")).isEqualTo(0.0);
    }

    @Test
    void record_首次成功记录() {
        SubAgentResult result = SubAgentResult.builder()
                .skillId("todo").success(true).output("ok")
                .tokensUsed(100).stepsExecuted(3).durationMs(500L).traceId("t1")
                .build();

        tracker.record("todo", result);

        Optional<SkillMetricsTracker.SkillMetrics> metrics = tracker.getMetrics("todo");
        assertThat(metrics).isPresent();
        assertThat(metrics.get().totalActivations()).isEqualTo(1);
        assertThat(metrics.get().successCount()).isEqualTo(1);
        assertThat(metrics.get().failureCount()).isEqualTo(0);
        assertThat(metrics.get().totalTokensUsed()).isEqualTo(100);
        assertThat(metrics.get().totalDurationMs()).isEqualTo(500L);
    }

    @Test
    void record_首次失败记录() {
        SubAgentResult result = SubAgentResult.builder()
                .skillId("todo").success(false).output("error")
                .tokensUsed(50).stepsExecuted(1).durationMs(200L).traceId("t2")
                .build();

        tracker.record("todo", result);

        Optional<SkillMetricsTracker.SkillMetrics> metrics = tracker.getMetrics("todo");
        assertThat(metrics).isPresent();
        assertThat(metrics.get().totalActivations()).isEqualTo(1);
        assertThat(metrics.get().successCount()).isEqualTo(0);
        assertThat(metrics.get().failureCount()).isEqualTo(1);
    }

    @Test
    void record_多次记录累加() {
        SubAgentResult success = SubAgentResult.builder()
                .skillId("todo").success(true).output("ok")
                .tokensUsed(100).stepsExecuted(3).durationMs(500L).traceId("t1")
                .build();
        SubAgentResult failure = SubAgentResult.builder()
                .skillId("todo").success(false).output("err")
                .tokensUsed(50).stepsExecuted(1).durationMs(200L).traceId("t2")
                .build();

        tracker.record("todo", success);
        tracker.record("todo", failure);
        tracker.record("todo", success);

        Optional<SkillMetricsTracker.SkillMetrics> metrics = tracker.getMetrics("todo");
        assertThat(metrics).isPresent();
        assertThat(metrics.get().totalActivations()).isEqualTo(3);
        assertThat(metrics.get().successCount()).isEqualTo(2);
        assertThat(metrics.get().failureCount()).isEqualTo(1);
        assertThat(metrics.get().totalTokensUsed()).isEqualTo(250);
        assertThat(metrics.get().totalDurationMs()).isEqualTo(1200L);
    }

    @Test
    void getSuccessRate_计算正确() {
        SubAgentResult success = SubAgentResult.builder()
                .skillId("s").success(true).output("ok")
                .tokensUsed(10).stepsExecuted(1).durationMs(100L).traceId("t")
                .build();
        SubAgentResult failure = SubAgentResult.builder()
                .skillId("s").success(false).output("err")
                .tokensUsed(10).stepsExecuted(1).durationMs(100L).traceId("t")
                .build();

        tracker.record("s", success);
        tracker.record("s", success);
        tracker.record("s", failure);

        assertThat(tracker.getSuccessRate("s")).isCloseTo(2.0 / 3.0, within(0.0001));
    }

    @Test
    void getSuccessRate_全部成功返回一() {
        SubAgentResult success = SubAgentResult.builder()
                .skillId("s").success(true).output("ok")
                .tokensUsed(10).stepsExecuted(1).durationMs(100L).traceId("t")
                .build();

        tracker.record("s", success);
        tracker.record("s", success);

        assertThat(tracker.getSuccessRate("s")).isEqualTo(1.0);
    }

    @Test
    void getSuccessRate_全部失败返回零() {
        SubAgentResult failure = SubAgentResult.builder()
                .skillId("s").success(false).output("err")
                .tokensUsed(10).stepsExecuted(1).durationMs(100L).traceId("t")
                .build();

        tracker.record("s", failure);
        tracker.record("s", failure);

        assertThat(tracker.getSuccessRate("s")).isEqualTo(0.0);
    }

    @Test
    void record_不同skillId独立追踪() {
        SubAgentResult todoResult = SubAgentResult.builder()
                .skillId("todo").success(true).output("ok")
                .tokensUsed(100).stepsExecuted(3).durationMs(500L).traceId("t1")
                .build();
        SubAgentResult scheduleResult = SubAgentResult.builder()
                .skillId("schedule").success(false).output("err")
                .tokensUsed(50).stepsExecuted(1).durationMs(200L).traceId("t2")
                .build();

        tracker.record("todo", todoResult);
        tracker.record("schedule", scheduleResult);

        assertThat(tracker.getMetrics("todo").get().totalActivations()).isEqualTo(1);
        assertThat(tracker.getMetrics("todo").get().successCount()).isEqualTo(1);
        assertThat(tracker.getMetrics("schedule").get().totalActivations()).isEqualTo(1);
        assertThat(tracker.getMetrics("schedule").get().failureCount()).isEqualTo(1);
    }
}
