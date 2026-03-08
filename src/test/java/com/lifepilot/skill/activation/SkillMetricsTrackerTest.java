package com.lifepilot.skill.activation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillMetricsTracker} 单元测试。
 *
 * <p>覆盖场景：首次激活计数、多次激活累加、lastActivatedAt 更新、未记录 skillId 返回 empty/0。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
class SkillMetricsTrackerTest {

    private SkillMetricsTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SkillMetricsTracker();
    }

    // ── 未记录的 skillId ──

    @Test
    void getMetrics_未记录的skillId_返回empty() {
        Optional<SkillMetricsTracker.SkillMetrics> result = tracker.getMetrics("unknown");
        assertThat(result).isEmpty();
    }

    @Test
    void getActivationCount_未记录的skillId_返回零() {
        assertThat(tracker.getActivationCount("unknown")).isEqualTo(0);
    }

    // ── 首次激活计数 ──

    @Test
    void recordActivation_首次激活_计数为1() {
        tracker.recordActivation("todo");

        assertThat(tracker.getActivationCount("todo")).isEqualTo(1);
        assertThat(tracker.getMetrics("todo")).isPresent();
        assertThat(tracker.getMetrics("todo").get().totalActivations()).isEqualTo(1);
    }

    // ── 多次激活累加 ──

    @Test
    void recordActivation_多次激活_计数累加() {
        tracker.recordActivation("todo");
        tracker.recordActivation("todo");
        tracker.recordActivation("todo");

        assertThat(tracker.getActivationCount("todo")).isEqualTo(3);
        assertThat(tracker.getMetrics("todo").get().totalActivations()).isEqualTo(3);
    }

    @Test
    void recordActivation_不同skillId_独立追踪() {
        tracker.recordActivation("todo");
        tracker.recordActivation("todo");
        tracker.recordActivation("schedule");

        assertThat(tracker.getActivationCount("todo")).isEqualTo(2);
        assertThat(tracker.getActivationCount("schedule")).isEqualTo(1);
    }

    // ── lastActivatedAt 更新 ──

    @Test
    void recordActivation_首次激活_设置lastActivatedAt() {
        Instant before = Instant.now();
        tracker.recordActivation("todo");
        Instant after = Instant.now();

        var metrics = tracker.getMetrics("todo").orElseThrow();
        assertThat(metrics.lastActivatedAt()).isNotNull();
        assertThat(metrics.lastActivatedAt()).isBetween(before, after);
    }

    @Test
    void recordActivation_再次激活_lastActivatedAt更新() throws InterruptedException {
        tracker.recordActivation("todo");
        Instant firstActivatedAt = tracker.getMetrics("todo").orElseThrow().lastActivatedAt();

        // 短暂等待确保时间戳不同
        Thread.sleep(5);

        tracker.recordActivation("todo");
        Instant secondActivatedAt = tracker.getMetrics("todo").orElseThrow().lastActivatedAt();

        assertThat(secondActivatedAt).isAfter(firstActivatedAt);
    }
}
