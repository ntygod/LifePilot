package com.lifepilot.multiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.multiagent.discovery.ToolDiscoveryService;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.execution.SpawnWorkersToolFactory;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.permission.service.PermissionRequestFactory;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.config.ToolAutoConfiguration;
import com.lifepilot.tool.registry.BuiltinToolRegistrar;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MultiAgentAutoConfiguration 条件装配测试。
 *
 * <p>验证 spawn_workers 工具可通过独立配置开关控制注册，
 * 且不会影响其他多 Agent 基础能力。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
class MultiAgentAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolAutoConfiguration.class,
                    MultiAgentAutoConfiguration.class))
            .withUserConfiguration(InfraBeansConfig.class)
            .withPropertyValues(
                    "lifepilot.tool.enabled=true",
                    "lifepilot.agent.multi-agent.enabled=true");

    @Test
    void 默认配置下不注册SpawnWorkers工具() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentExecutor.class);
            assertThat(context).hasSingleBean(ToolDiscoveryService.class);
            assertThat(context).doesNotHaveBean(SpawnWorkersToolFactory.class);
            assertThat(context).doesNotHaveBean("spawnWorkersTool");
            assertThat(context.getBean(MultiAgentProperties.class).getParallelWorker().isEnabled()).isFalse();

            var registrar = context.getBean(BuiltinToolRegistrar.class);
            registrar.registerAll();

            var registry = context.getBean(DynamicToolRegistry.class);
            assertThat(registry.resolve(SpawnWorkersToolFactory.TOOL_ID)).isEmpty();
            assertThat(context.getBean(ToolDiscoveryService.class).listAvailableTools())
                    .extracting(ToolDiscoveryService.ToolSummary::toolId)
                    .doesNotContain(SpawnWorkersToolFactory.TOOL_ID);
        });
    }

    @Test
    void 显式开启时注册SpawnWorkers工具() {
        contextRunner
                .withPropertyValues("lifepilot.agent.multi-agent.parallel-worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(SpawnWorkersToolFactory.class);
                    assertThat(context).hasBean("spawnWorkersTool");
                    assertThat(context.getBean(MultiAgentProperties.class).getParallelWorker().isEnabled()).isTrue();

                    var registrar = context.getBean(BuiltinToolRegistrar.class);
                    registrar.registerAll();

                    var registry = context.getBean(DynamicToolRegistry.class);
                    assertThat(registry.resolve(SpawnWorkersToolFactory.TOOL_ID)).isPresent();
                    assertThat(context.getBean(ToolDiscoveryService.class).listAvailableTools())
                            .extracting(ToolDiscoveryService.ToolSummary::toolId)
                            .contains(SpawnWorkersToolFactory.TOOL_ID);
                });
    }

    @TestConfiguration
    static class InfraBeansConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        GuardrailEngine guardrailEngine() {
            return mock(GuardrailEngine.class);
        }

        @Bean
        PermissionService permissionService() {
            return mock(PermissionService.class);
        }

        @Bean
        PermissionRequestFactory permissionRequestFactory() {
            return mock(PermissionRequestFactory.class);
        }

        @Bean
        MetaProperties metaProperties() {
            return new MetaProperties();
        }

        @Bean
        AgentOrchestrator agentOrchestrator() {
            return mock(AgentOrchestrator.class);
        }

        @Bean
        SharedScheduler sharedScheduler() {
            var scheduler = mock(SharedScheduler.class);
            var executor = mock(ScheduledExecutorService.class);
            when(scheduler.cleanup()).thenReturn(executor);
            when(scheduler.debounce()).thenReturn(executor);
            when(scheduler.heartbeat()).thenReturn(executor);
            return scheduler;
        }
    }
}
