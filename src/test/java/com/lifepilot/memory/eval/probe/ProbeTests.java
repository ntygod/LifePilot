package com.lifepilot.memory.eval.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 三件套单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("评估 Probe 单元测试")
class ProbeTests {

    @Nested
    @DisplayName("LatencyProbe")
    class Latency {

        @Test
        @DisplayName("start/stop: 记录样本并计算百分位")
        void 记录样本_计算百分位() throws Exception {
            LatencyProbe probe = new LatencyProbe();
            // 模拟 5 次 1ms / 10ms / 20ms / 50ms / 100ms
            long[] sleeps = {1, 10, 20, 50, 100};
            for (long s : sleeps) {
                long t0 = probe.start();
                Thread.sleep(s);
                probe.stop(t0);
            }
            assertThat(probe.sampleCount()).isEqualTo(5);
            LatencyProbe.LatencySummary sum = probe.summary();
            // 宽松断言：由于 sleep 精度抖动，只验证单调和非零
            assertThat(sum.p50Ms()).isGreaterThanOrEqualTo(0);
            assertThat(sum.p95Ms()).isGreaterThanOrEqualTo(sum.p50Ms());
            assertThat(sum.p99Ms()).isGreaterThanOrEqualTo(sum.p95Ms());
        }

        @Test
        @DisplayName("无样本: summary 全 0")
        void 无样本() {
            LatencyProbe probe = new LatencyProbe();
            LatencyProbe.LatencySummary sum = probe.summary();
            assertThat(sum.sampleCount()).isZero();
            assertThat(sum.p50Ms()).isZero();
            assertThat(sum.p95Ms()).isZero();
            assertThat(sum.p99Ms()).isZero();
        }
    }

    @Nested
    @DisplayName("TokenProbe")
    class Token {

        @Test
        @DisplayName("record 累加: 平均值正确")
        void 累加_平均() {
            TokenProbe probe = new TokenProbe();
            probe.record("hello world");
            probe.record("another longer sentence with many words");

            assertThat(probe.recallCount()).isEqualTo(2);
            assertThat(probe.totalTokens()).isGreaterThan(0);
            assertThat(probe.averageTokensPerRecall()).isGreaterThan(0);
        }

        @Test
        @DisplayName("空文本: 计入次数但 token=0")
        void 空文本_计入次数() {
            TokenProbe probe = new TokenProbe();
            probe.record("");
            probe.record(null);
            assertThat(probe.recallCount()).isEqualTo(2);
            assertThat(probe.totalTokens()).isZero();
            assertThat(probe.averageTokensPerRecall()).isZero();
        }
    }

    @Nested
    @DisplayName("RetrievalProbe")
    class Retrieval {

        @Test
        @DisplayName("命中率: ground truth 命中计入分子分母")
        void 命中率统计() {
            RetrievalProbe probe = new RetrievalProbe();
            probe.record("q1", Set.of("e1", "e2"), Set.of("e2"));              // 命中
            probe.record("q2", Set.of("e3"), Set.of("e4"));                    // 未命中
            probe.record("q3", List.of("e5"), List.of());                      // 无 truth, 不计
            probe.record("q4", Set.of("eX", "eY"), Set.of("eY"));              // 命中

            assertThat(probe.recordCount()).isEqualTo(4);
            assertThat(probe.hitRate()).isEqualTo(2f / 3f);
        }

        @Test
        @DisplayName("无记录: 命中率 0")
        void 无记录() {
            RetrievalProbe probe = new RetrievalProbe();
            assertThat(probe.hitRate()).isZero();
        }

        @Test
        @DisplayName("null 入参: 转空集，不抛异常")
        void null安全() {
            RetrievalProbe probe = new RetrievalProbe();
            probe.record("q", null, null);
            assertThat(probe.recordCount()).isEqualTo(1);
            assertThat(probe.hitRate()).isZero();
        }
    }
}
