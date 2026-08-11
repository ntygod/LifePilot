package com.lifepilot.agent.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EnvironmentPerceptor 单元测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class EnvironmentPerceptor_单元测试 {

    @Test
    void 缓存应按工具集合隔离() {
        var assessor = new CapabilityAssessor(10);
        assessor.recordExecution("tool.a", false, 50, "失败");
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        var first = perceptor.perceive(Set.of("tool.a"));
        var second = perceptor.perceive(Set.of("tool.b"));

        assertThat(first.toolHealth()).containsOnlyKeys("tool.a");
        assertThat(second.toolHealth()).containsOnlyKeys("tool.b");
        assertThat(second.toolHealth().get("tool.b").recentFailures()).isZero();
    }

    @Test
    void 相同工具集合应命中缓存() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        var first = perceptor.perceive(Set.of("tool.a"));
        assessor.recordExecution("tool.a", false, 50, "失败");
        var cached = perceptor.perceive(Set.of("tool.a"));

        assertThat(cached).isSameAs(first);
        assertThat(cached.toolHealth().get("tool.a").recentFailures()).isZero();
    }

    @Test
    void 构造器应拒绝非法缓存时间() {
        var assessor = new CapabilityAssessor(10);

        assertThatThrownBy(() -> new EnvironmentPerceptor(assessor, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环境感知缓存时间");
    }
}
