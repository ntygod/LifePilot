package com.lifepilot.observability.config;

import com.lifepilot.observability.evaluation.TrajectoryEvaluator;
import com.lifepilot.observability.guardrail.GuardrailAdvisor;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.observability.trace.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ObservabilityAutoConfiguration 条件 Bean 注册集成测试。
 *
 * <p>使用 {@link ApplicationContextRunner} 验证各子开关控制 Bean 注册行为。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
class ObservabilityAutoConfiguration_集成测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    ObservabilityAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:sqlite::memory:",
                    "spring.datasource.driver-class-name=org.sqlite.JDBC");

    @Test
    void 默认配置_所有Bean正常注册() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataRedactor.class);
            assertThat(context).hasSingleBean(TraceStepSerializer.class);
            assertThat(context).hasSingleBean(TraceContextPropagator.class);
            assertThat(context).hasSingleBean(TraceRecorderImpl.class);
            assertThat(context).hasSingleBean(TraceQuery.class);
            assertThat(context).hasSingleBean(TraceAdvisor.class);
            assertThat(context).hasSingleBean(GuardrailEngine.class);
            assertThat(context).hasSingleBean(GuardrailAdvisor.class);
            assertThat(context).hasSingleBean(TrajectoryEvaluator.class);
        });
    }

    @Test
    void trace关闭_Trace相关Bean不注册() {
        contextRunner
                .withPropertyValues("lifepilot.observability.trace.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceStepSerializer.class);
                    // TraceContextPropagator 无条件注册（护栏引擎也依赖）
                    assertThat(context).hasSingleBean(TraceContextPropagator.class);
                    assertThat(context).doesNotHaveBean(TraceRecorderImpl.class);
                    assertThat(context).doesNotHaveBean(TraceQuery.class);
                    assertThat(context).doesNotHaveBean(TraceAdvisor.class);
                    // DataRedactor 无条件注册
                    assertThat(context).hasSingleBean(DataRedactor.class);
                });
    }

    @Test
    void guardrail关闭_护栏相关Bean不注册() {
        contextRunner
                .withPropertyValues("lifepilot.observability.guardrail.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GuardrailEngine.class);
                    assertThat(context).doesNotHaveBean(GuardrailAdvisor.class);
                    // Trace 和评估仍然注册
                    assertThat(context).hasSingleBean(TraceRecorderImpl.class);
                    assertThat(context).hasSingleBean(TrajectoryEvaluator.class);
                });
    }

    @Test
    void evaluation关闭_评估Bean不注册() {
        contextRunner
                .withPropertyValues("lifepilot.observability.evaluation.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TrajectoryEvaluator.class);
                    // Trace 和护栏仍然注册
                    assertThat(context).hasSingleBean(TraceRecorderImpl.class);
                    assertThat(context).hasSingleBean(GuardrailEngine.class);
                });
    }
}
