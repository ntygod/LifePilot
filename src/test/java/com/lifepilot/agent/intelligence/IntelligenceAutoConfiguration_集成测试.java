package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.config.IntelligenceAutoConfiguration;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * IntelligenceAutoConfiguration 集成测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class IntelligenceAutoConfiguration_集成测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IntelligenceAutoConfiguration.class))
            .withBean(IntentMatcher.class, () -> mock(IntentMatcher.class));

    @Test
    void 默认应注册智能层组件() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CapabilityAssessor.class);
            assertThat(context).hasSingleBean(EnvironmentPerceptor.class);
            assertThat(context).hasSingleBean(AdaptiveDecisionEngine.class);
        });
    }

    @Test
    void 缺少意图匹配器时不注册决策引擎() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntelligenceAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(CapabilityAssessor.class);
                    assertThat(context).hasSingleBean(EnvironmentPerceptor.class);
                    assertThat(context).doesNotHaveBean(AdaptiveDecisionEngine.class);
                });
    }

    @Test
    void 关闭决策信号时应只关闭决策引擎() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.decision-signal-enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(CapabilityAssessor.class);
                    assertThat(context).hasSingleBean(EnvironmentPerceptor.class);
                    assertThat(context).doesNotHaveBean(AdaptiveDecisionEngine.class);
                });
    }

    @Test
    void 关闭智能层时不注册任何智能层组件() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CapabilityAssessor.class);
                    assertThat(context).doesNotHaveBean(EnvironmentPerceptor.class);
                    assertThat(context).doesNotHaveBean(AdaptiveDecisionEngine.class);
                });
    }

    @Test
    void 工具健康窗口配置应进入能力评估器() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.tool-health-window-size=1")
                .run(context -> {
                    var assessor = context.getBean(CapabilityAssessor.class);
                    assessor.recordExecution("tool.a", true, 10, null);
                    assessor.recordExecution("tool.a", false, 20, "失败");

                    var health = assessor.getToolHealth("tool.a");

                    assertThat(health.recentSuccesses()).isZero();
                    assertThat(health.recentFailures()).isEqualTo(1);
                    assertThat(health.avgLatencyMs()).isEqualTo(20);
                });
    }

    @Test
    void 环境缓存配置应进入环境感知器() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.environment-cache-ttl-seconds=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("环境感知缓存时间"));
    }
}
