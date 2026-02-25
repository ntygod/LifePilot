package com.lifepilot.skill.config;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.skill.activation.SkillLifecycleManager;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.activation.SubAgentFactory;
import com.lifepilot.skill.bridge.SkillToToolBridge;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.builtin.BuiltinSkillRegistrar;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.habit.HabitSkillProvider;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleSkillProvider;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.skill.builtin.todo.TodoSkillProvider;
import com.lifepilot.skill.memory.MemoryAccessEnforcer;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Skill 系统 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.skills.enabled=true}（默认）激活，
 * 注册 Skill 框架核心组件、持久化组件和内置 Skill 提供者。
 * 每个 Bean 使用 {@link ConditionalOnMissingBean} 允许用户覆盖。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@EnableConfigurationProperties(SkillConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.skills", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SkillAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SkillAutoConfiguration.class);

    // --- 框架组件 ---

    @Bean
    @ConditionalOnMissingBean
    public MemoryAccessEnforcer memoryAccessEnforcer() {
        log.info("Skill 系统: 注册 MemoryAccessEnforcer");
        return new MemoryAccessEnforcer();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillMetricsTracker skillMetricsTracker() {
        log.info("Skill 系统: 注册 SkillMetricsTracker");
        return new SkillMetricsTracker();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillDefinitionValidator skillDefinitionValidator(DynamicToolRegistry toolRegistry) {
        log.info("Skill 系统: 注册 SkillDefinitionValidator");
        return new SkillDefinitionValidator(toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillSearchIndex skillSearchIndex(LlmRouter llmRouter) {
        log.info("Skill 系统: 注册 SkillSearchIndex");
        return new SkillSearchIndex(llmRouter);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(SkillDefinitionValidator validator,
                                       SkillSearchIndex searchIndex,
                                       ApplicationEventPublisher eventPublisher) {
        log.info("Skill 系统: 注册 SkillRegistry");
        return new SkillRegistry(validator, searchIndex, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubAgentFactory subAgentFactory(SkillRegistry skillRegistry,
                                           AgentLoop agentLoop,
                                           DynamicToolRegistry toolRegistry,
                                           MemoryAccessEnforcer memoryAccessEnforcer) {
        log.info("Skill 系统: 注册 SubAgentFactory");
        return new SubAgentFactory(skillRegistry, agentLoop, toolRegistry, memoryAccessEnforcer);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillLifecycleManager skillLifecycleManager(SkillConfigProperties config,
                                                       SubAgentFactory subAgentFactory,
                                                       SkillMetricsTracker metricsTracker,
                                                       ApplicationEventPublisher eventPublisher) {
        log.info("Skill 系统: 注册 SkillLifecycleManager, maxConcurrentActivations={}",
                config.getMaxConcurrentActivations());
        return new SkillLifecycleManager(
                config.getMaxConcurrentActivations(),
                subAgentFactory, metricsTracker, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillToToolBridge skillToToolBridge(DynamicToolRegistry toolRegistry,
                                              SkillLifecycleManager lifecycleManager) {
        log.info("Skill 系统: 注册 SkillToToolBridge");
        return new SkillToToolBridge(toolRegistry, lifecycleManager);
    }

    // --- 持久化组件 ---

    @Bean
    @ConditionalOnMissingBean
    public TodoRepository todoRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 系统: 注册 TodoRepository");
        return new TodoRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleRepository scheduleRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 系统: 注册 ScheduleRepository");
        return new ScheduleRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public HabitRepository habitRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 系统: 注册 HabitRepository");
        return new HabitRepository(jdbcTemplate);
    }

    // --- 内置 Skill 提供者 ---

    @Bean
    @ConditionalOnMissingBean
    public TodoSkillProvider todoSkillProvider(TodoRepository todoRepository) {
        log.info("Skill 系统: 注册 TodoSkillProvider");
        return new TodoSkillProvider(todoRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleSkillProvider scheduleSkillProvider(ScheduleRepository scheduleRepository) {
        log.info("Skill 系统: 注册 ScheduleSkillProvider");
        return new ScheduleSkillProvider(scheduleRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public HabitSkillProvider habitSkillProvider(HabitRepository habitRepository) {
        log.info("Skill 系统: 注册 HabitSkillProvider");
        return new HabitSkillProvider(habitRepository);
    }

    // --- 注册器 ---

    @Bean
    @ConditionalOnMissingBean
    public BuiltinSkillRegistrar builtinSkillRegistrar(List<BuiltinSkillProvider> providers,
                                                       SkillRegistry skillRegistry,
                                                       DynamicToolRegistry toolRegistry) {
        log.info("Skill 系统: 注册 BuiltinSkillRegistrar, providers={}", providers.size());
        return new BuiltinSkillRegistrar(providers, skillRegistry, toolRegistry);
    }
}
