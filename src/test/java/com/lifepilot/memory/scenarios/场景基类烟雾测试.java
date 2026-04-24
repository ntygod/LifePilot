package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 场景测试基类烟雾测试 — 只验证 Spring 上下文能起、替身 Bean 都注入、基础 DSL 可用，
 * 不驱动完整 ReactAgentLoop（那是 Phase 4 S1-S16 各真实场景测试的事）。
 *
 * <p>通过本测试即说明 Task 9 的场景测试基础设施（Clock / Scheduler / Fixture /
 * FeedbackGateway / ReactAgentLoop 引用）装配完整，后续场景测试可以稳定继承。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("场景测试基类 烟雾测试")
class 场景基类烟雾测试 extends 场景测试基类 {

    @Test
    @DisplayName("Spring 上下文能起 + 替身 Bean 都已注入")
    void Spring上下文能起_替身都注入() {
        assertThat(agentLoop).as("ReactAgentLoop").isNotNull();
        assertThat(clock).as("MutableClock").isNotNull();
        assertThat(fixture).as("LlmFixture").isNotNull();
        assertThat(scheduler).as("ManualTaskScheduler").isNotNull();
        assertThat(queryApi).as("MemoryQueryApi").isNotNull();
        assertThat(feedbackProcessor).as("FeedbackProcessor").isNotNull();
        assertThat(feedbackGateway).as("FeedbackGateway").isNotNull();
    }

    @Test
    @DisplayName("clock.advance 能按 Duration 推进时钟")
    void 时钟可推进() {
        var t0 = clock.instant();
        时间推进(Duration.ofMinutes(5));
        assertThat(clock.instant()).isEqualTo(t0.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("每个测试方法前 testSessionId 重新生成")
    void Session前后隔离() {
        var previous = testSessionId;
        初始化Session();
        assertThat(testSessionId)
                .as("每次 初始化Session 应生成新的 sessionId")
                .isNotEqualTo(previous);
    }
}
