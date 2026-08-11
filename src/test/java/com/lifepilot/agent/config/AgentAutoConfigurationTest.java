package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.capability.ConversationCapabilityPlanner;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.store.document.MemoryDocumentRepository;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
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

    @Test
    void 工具经验后台记录超时配置应进入属性对象() {
        contextRunner
                .withPropertyValues("lifepilot.agent.loop.tool-experience-record-timeout-ms=450")
                .run(context -> {
                    var properties = context.getBean(AgentConfigProperties.class);

                    assertThat(properties.getLoop().getToolExperienceRecordTimeoutMs()).isEqualTo(450);
                });
    }

    @Test
    void 能力预发现开启时应注册轻量预发现器并绑定探针配置() {
        contextRunner
                .withUserConfiguration(ToolRegistryConfig.class)
                .withPropertyValues(
                        "lifepilot.agent.capability-discovery.control-prefix-chars=48",
                        "lifepilot.agent.capability-discovery.planning-probe-max-chars=160")
                .run(context -> {
                    var properties = context.getBean(AgentConfigProperties.class);

                    assertThat(context).hasSingleBean(ConversationCapabilityPlanner.class);
                    assertThat(properties.getCapabilityDiscovery().isEnabled()).isTrue();
                    assertThat(properties.getCapabilityDiscovery().getControlPrefixChars()).isEqualTo(48);
                    assertThat(properties.getCapabilityDiscovery().getPlanningProbeMaxChars()).isEqualTo(160);
                });
    }

    @Test
    void 能力预发现关闭时不应注册预发现器() {
        contextRunner
                .withUserConfiguration(ToolRegistryConfig.class)
                .withPropertyValues("lifepilot.agent.capability-discovery.enabled=false")
                .run(context -> {
                    var properties = context.getBean(AgentConfigProperties.class);

                    assertThat(context).doesNotHaveBean(ConversationCapabilityPlanner.class);
                    assertThat(properties.getCapabilityDiscovery().isEnabled()).isFalse();
                });
    }

    /**
     * 基础设施 Mock Bean 配置。
     *
     * <p>提供 AgentAutoConfiguration 其余 Bean 方法所需依赖
     * （JdbcTemplate、ObjectMapper、GenerationRouter），避免上下文启动失败。
     * AgentConfigProperties 由 {@code @EnableConfigurationProperties} 自动注册，
     * 无需手动创建。</p>
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class InfraBeansConfig {
        @Bean(name = "agentTestJdbcTemplate")
        JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }

        @Bean(name = "agentTestObjectMapper")
        ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean(name = "agentTestGenerationRouter")
        GenerationRouter generationRouter() { return mock(GenerationRouter.class); }

        @Bean(name = "agentTestMultimodalRouter")
        MultimodalRouter multimodalRouter() { return mock(MultimodalRouter.class); }

        @Bean(name = "agentTestPromptRegistry")
        PromptRegistry promptRegistry() { return mock(PromptRegistry.class); }

        @Bean(name = "agentTestSessionStoreRepository")
        SessionStoreRepository sessionStoreRepository() { return mock(SessionStoreRepository.class); }

        @Bean(name = "agentTestSessionTranscriptRepository")
        SessionTranscriptRepository sessionTranscriptRepository() { return mock(SessionTranscriptRepository.class); }

        @Bean(name = "agentTestTranscriptStore")
        TranscriptStore transcriptStore() { return mock(TranscriptStore.class); }

        @Bean(name = "agentTestMemoryDocumentRepository")
        MemoryDocumentRepository memoryDocumentRepository() { return mock(MemoryDocumentRepository.class); }

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

    /**
     * 模拟记忆系统 Bean 可用的配置。
     */
    @Configuration
    static class MemoryBeansConfig {
        @Bean
        HybridRetriever hybridRetriever() {
            return mock(HybridRetriever.class);
        }
    }

    /**
     * 只在能力预发现测试中提供工具注册表。
     */
    @Configuration
    static class ToolRegistryConfig {
        @Bean
        DynamicToolRegistry dynamicToolRegistry() {
            return new DynamicToolRegistry(_ -> {});
        }
    }

    /**
     * 用户自定义 ContextAssembler Bean。
     */
    @Configuration
    static class CustomContextAssemblerConfig {
        @Bean
        ContextAssembler customContextAssembler(AgentConfigProperties config, PromptRegistry promptRegistry) {
            return new ContextAssembler(config, promptRegistry,
                    null, null, null, null, null);
        }
    }
}
