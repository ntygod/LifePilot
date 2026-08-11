package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.config.IntelligenceAutoConfiguration;
import com.lifepilot.agent.intelligence.config.IntelligenceProperties;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void 经验匹配触发策略配置应进入属性对象() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.experience-match-trigger=disabled")
                .run(context -> assertThat(context.getBean(IntelligenceProperties.class)
                        .getExperienceMatchTrigger())
                        .isEqualTo(IntelligenceProperties.ExperienceMatchTrigger.DISABLED));
    }

    @Test
    void 默认经验匹配应轻量后台增强() {
        contextRunner.run(context -> {
            var properties = context.getBean(IntelligenceProperties.class);

            assertThat(properties.getExperienceMatchTimeoutMs()).isZero();
            assertThat(properties.getExperienceMatchMaxPending()).isEqualTo(1);
            assertThat(properties.getExperienceMatchBackgroundTimeoutMs()).isEqualTo(1200);
            assertThat(properties.getToolHealthSignalMaxTools()).isEqualTo(32);
        });
    }

    @Test
    void 经验匹配recent缓存配置应进入属性对象() {
        contextRunner
                .withPropertyValues(
                        "lifepilot.intelligence.experience-match-max-goal-chars=120",
                        "lifepilot.intelligence.experience-match-foreground-wait-cap-ms=25",
                        "lifepilot.intelligence.experience-match-background-timeout-ms=42",
                        "lifepilot.intelligence.experience-match-recent-ttl-seconds=12",
                        "lifepilot.intelligence.experience-match-recent-max=3",
                        "lifepilot.intelligence.tool-health-signal-max-tools=5")
                .run(context -> {
                    var properties = context.getBean(IntelligenceProperties.class);
                    assertThat(properties.getExperienceMatchMaxGoalChars()).isEqualTo(120);
                    assertThat(properties.getExperienceMatchForegroundWaitCapMs()).isEqualTo(25);
                    assertThat(properties.getExperienceMatchBackgroundTimeoutMs()).isEqualTo(42);
                    assertThat(properties.getExperienceMatchRecentTtlSeconds()).isEqualTo(12);
                    assertThat(properties.getExperienceMatchRecentMax()).isEqualTo(3);
                    assertThat(properties.getToolHealthSignalMaxTools()).isEqualTo(5);
                });
    }

    @Test
    void 非法后台经验匹配超时配置应阻止决策引擎注册() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.experience-match-background-timeout-ms=-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("经验匹配后台任务超时时间"));
    }

    @Test
    void 非法工具健康信号扫描上限应阻止决策引擎注册() {
        contextRunner
                .withPropertyValues("lifepilot.intelligence.tool-health-signal-max-tools=-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("工具健康扫描上限"));
    }

    @Test
    void 经验匹配前台等待应被装配层限制() {
        contextRunner
                .withPropertyValues(
                        "lifepilot.intelligence.experience-match-timeout-ms=500",
                        "lifepilot.intelligence.experience-match-foreground-wait-cap-ms=25",
                        "lifepilot.intelligence.experience-match-min-goal-chars=0",
                        "lifepilot.intelligence.experience-match-trigger=always")
                .run(context -> {
                    var matcher = context.getBean(IntentMatcher.class);
                    var started = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    when(matcher.match("帮我整理项目计划")).thenAnswer(invocation -> {
                        started.countDown();
                        release.await(1, TimeUnit.SECONDS);
                        return Optional.empty();
                    });

                    var engine = context.getBean(AdaptiveDecisionEngine.class);
                    long startedAt = System.nanoTime();
                    var signal = engine.buildDecisionSignal("帮我整理项目计划", Set.of());
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                    release.countDown();

                    assertThat(started.await(200, TimeUnit.MILLISECONDS)).isTrue();
                    assertThat(signal.experienceHint()).isNull();
                    assertThat(elapsedMs).isLessThan(200);
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
