package com.lifepilot.agent.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * CapabilityAssessor 单元测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class CapabilityAssessor_单元测试 {

    @Test
    void 工具健康统计应只保留最近窗口内结果() {
        var assessor = new CapabilityAssessor(3);

        assessor.recordExecution("web.search", true, 10, null);
        assessor.recordExecution("web.search", true, 20, null);
        assessor.recordExecution("web.search", false, 30, "第一次失败");
        assessor.recordExecution("web.search", false, 40, "第二次失败");

        var health = assessor.getToolHealth("web.search");

        assertThat(health.recentSuccesses()).isEqualTo(1);
        assertThat(health.recentFailures()).isEqualTo(2);
        assertThat(health.avgLatencyMs()).isEqualTo(30);
        assertThat(health.lastError()).isEqualTo("第二次失败");
        assertThat(health.successRate()).isCloseTo(1.0f / 3.0f, within(0.0001f));
    }

    @Test
    void 构造器应拒绝非法滑动窗口大小() {
        assertThatThrownBy(() -> new CapabilityAssessor(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具健康滑动窗口大小");
    }
}
