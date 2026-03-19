package com.lifepilot.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.config.SkillAutoConfiguration;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executors;

import static org.mockito.Mockito.mock;

/**
 * Skill 模块集成测试共享配置。
 *
 * <p>提供 SkillAutoConfiguration 所需的外部依赖 Mock，
 * 避免多个测试类各自定义同名 Bean 导致 {@code BeanDefinitionOverrideException}。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@TestConfiguration
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        SkillAutoConfiguration.class
})
public class SkillTestSupport {

    @Bean
    DynamicToolRegistry dynamicToolRegistry(GuardrailEngine guardrailEngine,
                                            ApplicationEventPublisher eventPublisher) {
        return new DynamicToolRegistry(guardrailEngine, eventPublisher);
    }

    @Bean
    LlmRouter llmRouter() {
        return mock(LlmRouter.class);
    }

    @Bean
    GuardrailEngine guardrailEngine() {
        return mock(GuardrailEngine.class);
    }

    @Bean
    HybridRetriever hybridRetriever() {
        return mock(HybridRetriever.class);
    }

    @Bean
    SemanticMemory semanticMemory() {
        return mock(SemanticMemory.class);
    }

    @Bean
    PromptRegistry promptRegistry() {
        return mock(PromptRegistry.class);
    }

    @Bean
    DataStoreManager dataStoreManager() {
        return mock(DataStoreManager.class);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    SharedScheduler sharedScheduler() {
        return mock(SharedScheduler.class, invocation -> {
            if (invocation.getMethod().getName().equals("debounce")
                    || invocation.getMethod().getName().equals("cleanup")
                    || invocation.getMethod().getName().equals("heartbeat")) {
                return Executors.newSingleThreadScheduledExecutor();
            }
            return null;
        });
    }
}
