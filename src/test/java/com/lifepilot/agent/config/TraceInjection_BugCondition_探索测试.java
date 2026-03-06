package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.observability.config.ObservabilityAutoConfiguration;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bug Condition 探索测试 — 验证 @ConditionalOnBean(TraceRecorder.class) 时序问题。
 *
 * <p>当 TraceRecorder bean 已在容器中注册时，AgentLoop 的 traceRecorder 字段应为非 null。
 * 在未修复代码上，此测试预期 FAIL（traceRecorder 为 null），确认 bug 存在。</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
class TraceInjection_BugCondition_探索测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservabilityAutoConfiguration.class,
                    AgentAutoConfiguration.class
            ))
            .withUserConfiguration(MinimalInfraConfig.class)
            .withPropertyValues(
                    "lifepilot.agent.enabled=true",
                    "lifepilot.observability.trace.enabled=true"
            );

    @Test
    @DisplayName("TraceRecorder 已注册时 AgentLoop 的 traceRecorder 字段应为非 null")
    void TraceRecorder已注册时_AgentLoop应注入TraceRecorder() {
        contextRunner.run(context -> {
            // 验证 TraceRecorder bean 确实存在
            assertThat(context).hasSingleBean(TraceRecorder.class);

            // 验证 AgentLoop bean 存在
            assertThat(context).hasSingleBean(AgentLoop.class);

            // 通过反射读取 AgentLoop.traceRecorder 私有字段
            AgentLoop agentLoop = context.getBean(AgentLoop.class);
            Field traceRecorderField = AgentLoop.class.getDeclaredField("traceRecorder");
            traceRecorderField.setAccessible(true);
            Object traceRecorderValue = traceRecorderField.get(agentLoop);

            // 期望：traceRecorder 非 null（与容器中的 TraceRecorder bean 为同一实例）
            // 未修复代码上此断言将 FAIL，确认 bug 存在
            assertThat(traceRecorderValue)
                    .as("AgentLoop.traceRecorder 应为非 null（TraceRecorder bean 已注册）")
                    .isNotNull();

            assertThat(traceRecorderValue)
                    .as("AgentLoop.traceRecorder 应与容器中的 TraceRecorder bean 为同一实例")
                    .isSameAs(context.getBean(TraceRecorder.class));
        });
    }

    /**
     * 最小基础设施 Mock 配置 — 提供 AgentAutoConfiguration 和 ObservabilityAutoConfiguration 所需的依赖。
     */
    @TestConfiguration
    static class MinimalInfraConfig {
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean LlmRouter llmRouter() { return mock(LlmRouter.class); }
        @Bean MultimodalRouter multimodalRouter() { return mock(MultimodalRouter.class); }
        @Bean PromptRegistry promptRegistry() { return mock(PromptRegistry.class); }
        @Bean AgentToolProvider agentToolProvider() { return mock(AgentToolProvider.class); }
    }
}
