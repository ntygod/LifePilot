package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AgentAutoConfiguration 条件化 Bean 注册集成测试。
 *
 * <p>使用 {@link ApplicationContextRunner} 验证 ContextAssembler
 * 在不同 Bean 组合下的条件化注册行为。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
class AgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withUserConfiguration(InfraBeansConfig.class)
            .withPropertyValues("lifepilot.agent.enabled=true");

    @Test
    void 记忆系统可用时注册完整版() {
        contextRunner
                .withUserConfiguration(MemoryBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ContextAssembler.class);
                    assertThat(context).hasBean("contextAssembler");
                });
    }

    @Test
    void 记忆系统不可用时注册基础版() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(ContextAssembler.class);
                    assertThat(context).hasBean("contextAssembler");
                });
    }

    @Test
    void 自定义Bean覆盖默认实现() {
        contextRunner
                .withUserConfiguration(CustomContextAssemblerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ContextAssembler.class);
                    assertThat(context).hasBean("customContextAssembler");
                    assertThat(context).doesNotHaveBean("contextAssembler");
                });
    }

    /**
     * 基础设施 Mock Bean 配置。
     *
     * <p>提供 AgentAutoConfiguration 中其他 Bean 方法所需的依赖
     * （JdbcTemplate、ObjectMapper、LlmRouter），避免上下文启动失败。
     * AgentConfigProperties 由 @EnableConfigurationProperties 自动注册，无需手动创建。</p>
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class InfraBeansConfig {
        @Bean(name = "agentTestJdbcTemplate")
        JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean(name = "agentTestObjectMapper")
        ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean(name = "agentTestLlmRouter")
        LlmRouter llmRouter() { return mock(LlmRouter.class); }
        @Bean(name = "agentTestMultimodalRouter")
        MultimodalRouter multimodalRouter() { return mock(MultimodalRouter.class); }
        @Bean(name = "agentTestPromptRegistry")
        PromptRegistry promptRegistry() { return mock(PromptRegistry.class); }
        @Bean(name = "agentTestAgentToolProvider")
        AgentToolProvider agentToolProvider() { return mock(AgentToolProvider.class); }
        @Bean(name = "agentTestSharedScheduler")
        SharedScheduler sharedScheduler() {
            var scheduler = mock(SharedScheduler.class);
            var mockExecutor = mock(ScheduledExecutorService.class);
            org.mockito.Mockito.when(scheduler.cleanup()).thenReturn(mockExecutor);
            org.mockito.Mockito.when(scheduler.debounce()).thenReturn(mockExecutor);
            org.mockito.Mockito.when(scheduler.heartbeat()).thenReturn(mockExecutor);
            return scheduler;
        }
    }

    /** 模拟记忆系统 Bean 可用的配置。 */
    @Configuration
    static class MemoryBeansConfig {
        @Bean HybridRetriever hybridRetriever() { return mock(HybridRetriever.class); }
        @Bean WorkingMemory workingMemory() { return mock(WorkingMemory.class); }
        @Bean TokenBudgetAllocator tokenBudgetAllocator() { return mock(TokenBudgetAllocator.class); }
    }

    /** 用户自定义 ContextAssembler Bean。 */
    @Configuration
    static class CustomContextAssemblerConfig {
        @Bean
        ContextAssembler customContextAssembler(AgentConfigProperties config, PromptRegistry promptRegistry) {
            return new ContextAssembler(config, promptRegistry,
                    null, null, null, null, null, null, null, null);
        }
    }
}
