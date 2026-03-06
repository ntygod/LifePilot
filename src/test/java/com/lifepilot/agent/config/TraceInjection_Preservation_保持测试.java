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
 * Preservation 保持测试 — 验证修复不引入回归。
 *
 * <p>测试场景：
 * <ul>
 *   <li>TraceRecorder 未注册时（trace.enabled=false），AgentLoop 正常创建且 traceRecorder 为 null</li>
 *   <li>用户自定义 AgentLoop bean 时，@ConditionalOnMissingBean 跳过默认创建</li>
 *   <li>其他可选依赖未注册时，AgentLoop 仍正常创建</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
class TraceInjection_Preservation_保持测试 {

    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservabilityAutoConfiguration.class,
                    AgentAutoConfiguration.class
            ))
            .withUserConfiguration(MinimalInfraConfig.class)
            .withPropertyValues("lifepilot.agent.enabled=true");

    @Test
    @DisplayName("Trace 禁用时 AgentLoop 正常创建且 traceRecorder 为 null")
    void Trace禁用时_AgentLoop正常创建且traceRecorder为null() {
        baseRunner
                .withPropertyValues("lifepilot.observability.trace.enabled=false")
                .run(context -> {
                    // TraceRecorder bean 不应存在
                    assertThat(context).doesNotHaveBean(TraceRecorder.class);

                    // AgentLoop 应正常创建
                    assertThat(context).hasSingleBean(AgentLoop.class);

                    // traceRecorder 字段应为 null
                    AgentLoop agentLoop = context.getBean(AgentLoop.class);
                    Field field = AgentLoop.class.getDeclaredField("traceRecorder");
                    field.setAccessible(true);
                    assertThat(field.get(agentLoop))
                            .as("Trace 禁用时 AgentLoop.traceRecorder 应为 null")
                            .isNull();
                });
    }

    @Test
    @DisplayName("用户自定义 AgentLoop bean 时跳过默认创建")
    void 用户自定义AgentLoop时_跳过默认创建() {
        baseRunner
                .withPropertyValues("lifepilot.observability.trace.enabled=true")
                .withUserConfiguration(CustomAgentLoopConfig.class)
                .run(context -> {
                    // 应只有用户自定义的 AgentLoop bean
                    assertThat(context).hasSingleBean(AgentLoop.class);
                    assertThat(context).hasBean("customAgentLoop");
                });
    }

    @Test
    @DisplayName("可选依赖全部缺失时 AgentLoop 仍正常创建")
    void 可选依赖全部缺失时_AgentLoop仍正常创建() {
        // 不注册 WorkingMemory、EpisodicMemory、ConversationHistoryStore、RealtimeExtractor 等
        baseRunner
                .withPropertyValues("lifepilot.observability.trace.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentLoop.class);

                    // 验证可选字段为 null
                    AgentLoop agentLoop = context.getBean(AgentLoop.class);
                    Field wmField = AgentLoop.class.getDeclaredField("workingMemory");
                    wmField.setAccessible(true);
                    assertThat(wmField.get(agentLoop))
                            .as("WorkingMemory 未注册时应为 null")
                            .isNull();

                    Field reField = AgentLoop.class.getDeclaredField("realtimeExtractor");
                    reField.setAccessible(true);
                    assertThat(reField.get(agentLoop))
                            .as("RealtimeExtractor 未注册时应为 null")
                            .isNull();
                });
    }

    /**
     * 最小基础设施 Mock 配置。
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

    /**
     * 用户自定义 AgentLoop bean 配置。
     */
    @TestConfiguration
    static class CustomAgentLoopConfig {
        @Bean
        AgentLoop customAgentLoop() {
            return mock(AgentLoop.class);
        }
    }
}
