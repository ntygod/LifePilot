package com.lifepilot.workflow.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.engine.StepExecutor;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import com.lifepilot.workflow.trigger.WorkflowTriggerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 工作流引擎 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.workflow.enabled=true}（默认）激活，
 * 注册工作流引擎全部 Bean：解析器、表达式引擎、持久化仓储、步骤执行器、
 * 注册中心和核心引擎。
 *
 * <p>依赖已有模块：SkillRegistry、SkillActivator、DynamicToolRegistry、
 * LlmRouter、JdbcTemplate。
 *
 * @author zsg
 * @since 2026-02-26
 */
@AutoConfiguration
@EnableConfigurationProperties(WorkflowConfigProperties.class)
@EnableScheduling
@ConditionalOnProperty(name = "lifepilot.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public WorkflowYamlParser workflowYamlParser() {
        return new WorkflowYamlParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowYamlPrinter workflowYamlPrinter() {
        return new WorkflowYamlPrinter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExpressionEngine workflowExpressionEngine() {
        return new ExpressionEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRepository workflowRepository(JdbcTemplate jdbcTemplate,
                                                   WorkflowYamlParser yamlParser) {
        return new WorkflowRepository(jdbcTemplate, yamlParser);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry(WorkflowRepository repository,
                                              WorkflowYamlParser parser,
                                              WorkflowYamlPrinter printer,
                                              WorkflowConfigProperties config,
                                              TaskScheduler workflowTaskScheduler) {
        var registry = new WorkflowRegistry(repository, parser, printer);
        registry.setConfigProperties(config);
        registry.setTaskScheduler(workflowTaskScheduler);
        log.info("工作流注册中心初始化完成");
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public StepExecutor workflowStepExecutor(SkillRegistry skillRegistry,
                                              SkillActivator skillActivator,
                                              DynamicToolRegistry toolRegistry,
                                              LlmRouter llmRouter,
                                              WorkflowConfigProperties config) {
        return new StepExecutor(skillRegistry, skillActivator, toolRegistry, llmRouter, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEngine workflowEngine(WorkflowRegistry registry,
                                          StepExecutor stepExecutor,
                                          ExpressionEngine expressionEngine,
                                          WorkflowRepository repository,
                                          WorkflowConfigProperties config) {
        log.info("工作流执行引擎初始化完成");
        return new WorkflowEngine(registry, stepExecutor, expressionEngine, repository, config);
    }

    @Bean
    @ConditionalOnMissingBean(name = "workflowTaskScheduler")
    public TaskScheduler workflowTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("workflow-cron-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowTriggerManager workflowTriggerManager(WorkflowEngine engine,
                                                          WorkflowRegistry registry,
                                                          WorkflowRepository repository,
                                                          TaskScheduler workflowTaskScheduler) {
        return new WorkflowTriggerManager(engine, registry, repository, workflowTaskScheduler);
    }

    /**
     * 应用启动完成后触发崩溃恢复、触发器注册和 YAML 热加载。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        // 延迟注入 triggerManager 到 registry，避免循环依赖
        ctx.getBean(WorkflowRegistry.class).setTriggerManager(ctx.getBean(WorkflowTriggerManager.class));
        ctx.getBean(WorkflowEngine.class).recoverInterruptedInstances();
        ctx.getBean(WorkflowTriggerManager.class).registerAllTriggers();
        ctx.getBean(WorkflowRegistry.class).startScheduledScan();
        log.info("工作流崩溃恢复、触发器注册和 YAML 热加载已完成");
    }
}
