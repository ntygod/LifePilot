package com.lifepilot.agent.task.proactive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BehaviorHealthTracker 单元测试
 *
 * @author zsg
 * @since 2026-04-15
 */
class BehaviorHealthTracker_单元测试 {

    private BehaviorHealthTracker tracker;

    @BeforeEach
    void 初始化() {
        // 默认阈值: degradeThreshold=3, recoveryInterval=5
        tracker = new BehaviorHealthTracker();
    }

    // ── 初始状态 ──

    @Test
    void 初始状态未降级_tryActivate返回true() {
        assertThat(tracker.isDegraded("pluginA")).isFalse();
        assertThat(tracker.tryActivate("pluginA")).isTrue();
    }

    @Test
    void 初始状态连续失败次数为零() {
        assertThat(tracker.getConsecutiveFailures("pluginA")).isZero();
    }

    // ── 连续失败未达阈值 ──

    @Test
    void 连续失败未达阈值不降级() {
        tracker.recordFailure("pluginA", new RuntimeException("错误1"));
        tracker.recordFailure("pluginA", new RuntimeException("错误2"));

        assertThat(tracker.getConsecutiveFailures("pluginA")).isEqualTo(2);
        assertThat(tracker.isDegraded("pluginA")).isFalse();
        assertThat(tracker.tryActivate("pluginA")).isTrue();
    }

    // ── 达到阈值触发降级 ──

    @Test
    void 连续3次失败触发降级() {
        for (int i = 0; i < 3; i++) {
            tracker.recordFailure("pluginA", new RuntimeException("错误" + (i + 1)));
        }

        assertThat(tracker.getConsecutiveFailures("pluginA")).isEqualTo(3);
        assertThat(tracker.isDegraded("pluginA")).isTrue();
    }

    @Test
    void recordFailure返回当前连续失败次数() {
        int count1 = tracker.recordFailure("pluginA", new RuntimeException("e1"));
        int count2 = tracker.recordFailure("pluginA", new RuntimeException("e2"));
        int count3 = tracker.recordFailure("pluginA", new RuntimeException("e3"));

        assertThat(count1).isEqualTo(1);
        assertThat(count2).isEqualTo(2);
        assertThat(count3).isEqualTo(3);
    }

    // ── 降级后 tryActivate ──

    @Test
    void 降级后tryActivate返回false() {
        降级插件("pluginA");

        // 降级后前 4 次心跳都应返回 false（recoveryInterval=5）
        for (int i = 0; i < 4; i++) {
            assertThat(tracker.tryActivate("pluginA"))
                    .as("第 %d 次心跳应返回 false", i + 1)
                    .isFalse();
        }
    }

    @Test
    void 降级后第5次tryActivate尝试恢复返回true() {
        降级插件("pluginA");

        // 前 4 次返回 false
        for (int i = 0; i < 4; i++) {
            tracker.tryActivate("pluginA");
        }
        // 第 5 次达到 recoveryInterval，尝试恢复
        assertThat(tracker.tryActivate("pluginA")).isTrue();
    }

    @Test
    void 恢复尝试后计数器归零_再过5次心跳再次尝试() {
        降级插件("pluginA");

        // 第一轮：4 次 false + 第 5 次 true
        for (int i = 0; i < 4; i++) {
            tracker.tryActivate("pluginA");
        }
        assertThat(tracker.tryActivate("pluginA")).isTrue();

        // 第二轮：如果没有 recordSuccess，仍处于降级，再走一个周期
        for (int i = 0; i < 4; i++) {
            assertThat(tracker.tryActivate("pluginA")).isFalse();
        }
        assertThat(tracker.tryActivate("pluginA")).isTrue();
    }

    // ── recordSuccess 恢复 ──

    @Test
    void 恢复尝试后recordSuccess完全恢复() {
        降级插件("pluginA");

        // 模拟恢复尝试窗口到达
        for (int i = 0; i < 5; i++) {
            tracker.tryActivate("pluginA");
        }
        // 恢复尝试中执行成功
        tracker.recordSuccess("pluginA");

        assertThat(tracker.isDegraded("pluginA")).isFalse();
        assertThat(tracker.getConsecutiveFailures("pluginA")).isZero();
        assertThat(tracker.tryActivate("pluginA")).isTrue();
    }

    @Test
    void recordSuccess重置连续失败计数() {
        tracker.recordFailure("pluginA", new RuntimeException("e1"));
        tracker.recordFailure("pluginA", new RuntimeException("e2"));
        assertThat(tracker.getConsecutiveFailures("pluginA")).isEqualTo(2);

        tracker.recordSuccess("pluginA");

        assertThat(tracker.getConsecutiveFailures("pluginA")).isZero();
    }

    @Test
    void 成功后再失败从零开始计数() {
        // 先累积 2 次失败
        tracker.recordFailure("pluginA", new RuntimeException("e1"));
        tracker.recordFailure("pluginA", new RuntimeException("e2"));

        // 成功重置
        tracker.recordSuccess("pluginA");

        // 重新失败，计数从 1 开始
        int count = tracker.recordFailure("pluginA", new RuntimeException("e3"));
        assertThat(count).isEqualTo(1);
        assertThat(tracker.getConsecutiveFailures("pluginA")).isEqualTo(1);
        assertThat(tracker.isDegraded("pluginA")).isFalse();
    }

    // ── 多插件隔离 ──

    @Test
    void 不同插件状态互不影响() {
        降级插件("pluginA");

        assertThat(tracker.isDegraded("pluginA")).isTrue();
        assertThat(tracker.isDegraded("pluginB")).isFalse();
        assertThat(tracker.tryActivate("pluginB")).isTrue();
        assertThat(tracker.getConsecutiveFailures("pluginB")).isZero();
    }

    // ── 自定义阈值 ──

    @Test
    void 自定义阈值和恢复间隔() {
        var custom = new BehaviorHealthTracker(2, 3);

        // 2 次失败即降级
        custom.recordFailure("p", new RuntimeException("e1"));
        assertThat(custom.isDegraded("p")).isFalse();

        custom.recordFailure("p", new RuntimeException("e2"));
        assertThat(custom.isDegraded("p")).isTrue();

        // 3 次心跳后尝试恢复
        assertThat(custom.tryActivate("p")).isFalse();
        assertThat(custom.tryActivate("p")).isFalse();
        assertThat(custom.tryActivate("p")).isTrue();
    }

    @Test
    void 阈值下限为1() {
        // 传入 0 和 0，应被钳位到 1
        var minimal = new BehaviorHealthTracker(0, 0);

        // 1 次失败即降级
        minimal.recordFailure("p", new RuntimeException("e"));
        assertThat(minimal.isDegraded("p")).isTrue();

        // 1 次心跳即尝试恢复
        assertThat(minimal.tryActivate("p")).isTrue();
    }

    // ── 辅助方法 ──

    /** 将指定插件推入降级状态（连续 3 次失败）。 */
    private void 降级插件(String name) {
        for (int i = 0; i < 3; i++) {
            tracker.recordFailure(name, new RuntimeException("降级失败" + (i + 1)));
        }
        assertThat(tracker.isDegraded(name))
                .as("前置条件：插件应已降级")
                .isTrue();
    }
}
