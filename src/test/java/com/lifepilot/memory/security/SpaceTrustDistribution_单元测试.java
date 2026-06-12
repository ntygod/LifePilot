package com.lifepilot.memory.security;

import com.lifepilot.memory.governance.security.SpaceTrustDistribution;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SpaceTrustDistribution 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class SpaceTrustDistribution_单元测试 {

    @Test
    void 样本不足时距离为0() {
        var dist = new SpaceTrustDistribution(1000);
        dist.observe("s1", 0.5f);
        dist.observe("s1", 0.6f);
        // 少于 10 个样本，返回 0
        assertThat(dist.mahalanobisDistance("s1", 0.1f)).isEqualTo(0f);
    }

    @Test
    void 稳定分布下异常值被识别() {
        var dist = new SpaceTrustDistribution(1000);
        // 填充 20 条 0.8 左右的样本（正常分布）
        for (int i = 0; i < 20; i++) {
            dist.observe("s1", 0.8f + (i % 5) * 0.01f);
        }
        float d = dist.mahalanobisDistance("s1", 0.1f);  // 偏离均值约 0.7
        assertThat(d).isGreaterThan(3.0f);
        assertThat(dist.isOutlier("s1", 0.1f, 3.0f)).isTrue();
        assertThat(dist.isOutlier("s1", 0.82f, 3.0f)).isFalse();
    }

    @Test
    void 滑动窗口容量控制() {
        var dist = new SpaceTrustDistribution(10);
        for (int i = 0; i < 25; i++) {
            dist.observe("s1", 0.5f);
        }
        assertThat(dist.sampleCount("s1")).isLessThanOrEqualTo(10);
    }

    @Test
    void 空spaceId静默忽略() {
        var dist = new SpaceTrustDistribution(100);
        dist.observe(null, 0.5f);
        dist.observe("", 0.5f);
        assertThat(dist.sampleCount(null)).isEqualTo(0);
        assertThat(dist.sampleCount("any")).isEqualTo(0);
    }

    @Test
    void 多space隔离() {
        var dist = new SpaceTrustDistribution(1000);
        for (int i = 0; i < 15; i++) {
            dist.observe("s1", 0.8f);
            dist.observe("s2", 0.2f);
        }
        // s1 中 0.8 正常、0.2 异常
        assertThat(dist.isOutlier("s1", 0.2f, 3.0f)).isTrue();
        // s2 中 0.2 正常、0.8 异常
        assertThat(dist.isOutlier("s2", 0.8f, 3.0f)).isTrue();
        assertThat(dist.isOutlier("s2", 0.2f, 3.0f)).isFalse();
    }

    @Test
    void 零方差时不认为异常() {
        var dist = new SpaceTrustDistribution(100);
        // 全是 0.5
        for (int i = 0; i < 15; i++) {
            dist.observe("s1", 0.5f);
        }
        // stddev ≈ 0，按规则返回 0 距离
        assertThat(dist.mahalanobisDistance("s1", 0.1f)).isEqualTo(0f);
    }
}
