package com.lifepilot.agent.initiative.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.initiative.InitiativeEngine;
import com.lifepilot.agent.initiative.Thinker;
import com.lifepilot.agent.initiative.express.ConversationInitiator;
import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.maturity.MaturityModel;
import com.lifepilot.agent.initiative.model.ThoughtState;
import com.lifepilot.agent.initiative.pool.ThoughtPool;
import com.lifepilot.agent.initiative.pool.ThoughtRepository;
import com.lifepilot.agent.initiative.signal.InitiativeEventListener;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * InitiativeAutoConfiguration 集成测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class InitiativeAutoConfiguration_集成测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InitiativeAutoConfiguration.class))
            .withUserConfiguration(RequiredBeansConfig.class);

    @Test
    void 默认配置应注册主动引擎组件() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MaturityModel.class);
            assertThat(context).hasSingleBean(ThoughtPool.class);
            assertThat(context).hasSingleBean(Gatekeeper.class);
            assertThat(context).hasSingleBean(Thinker.class);
            assertThat(context).hasSingleBean(ConversationInitiator.class);
            assertThat(context).hasSingleBean(InitiativeEngine.class);
            assertThat(context).hasSingleBean(InitiativeEventListener.class);
        });
    }

    @Test
    void 显式关闭时不注册主动引擎组件() {
        contextRunner
                .withPropertyValues("lifepilot.initiative.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InitiativeEngine.class);
                    assertThat(context).doesNotHaveBean(ThoughtPool.class);
                    assertThat(context).doesNotHaveBean(Gatekeeper.class);
                });
    }

    static class RequiredBeansConfig {
        @Bean
        AgentOrchestrator agentOrchestrator() {
            return mock(AgentOrchestrator.class);
        }

        @Bean
        MemoryAttentionService memoryAttentionService() {
            return mock(MemoryAttentionService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ThoughtRepository thoughtRepository() {
            var repository = mock(ThoughtRepository.class);
            when(repository.findByState(any(ThoughtState.class))).thenReturn(List.of());
            return repository;
        }
    }
}
