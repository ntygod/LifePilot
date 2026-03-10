package com.lifepilot.workflow.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.engine.DagScheduler;
import com.lifepilot.workflow.engine.StepExecutor;
import com.lifepilot.workflow.engine.WakeupScheduler;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.engine.WorkflowEventRecorder;
import com.lifepilot.workflow.engine.WorkflowRunner;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.parser.WorkflowYamlParser;
import com.lifepilot.workflow.parser.WorkflowYamlPrinter;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import com.lifepilot.workflow.trigger.WorkflowTriggerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    public WorkflowYamlParser workflowYamlParser(WorkflowConfigProperties config) {
        return new WorkflowYamlParser(config);
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
    public DagScheduler dagScheduler() {
        return new DagScheduler();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEventRecorder workflowEventRecorder(JdbcTemplate jdbcTemplate,
                                                        WorkflowConfigProperties config) {
        return new WorkflowEventRecorder(jdbcTemplate, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry(WorkflowRepository repository,
                                              WorkflowYamlParser parser,
                                              WorkflowYamlPrinter printer,
                                              WorkflowConfigProperties config,
                                              TaskScheduler workflowTaskScheduler,
                                              DagScheduler dagScheduler) {
        var registry = new WorkflowRegistry(repository, parser, printer);
        registry.setConfigProperties(config);
        registry.setTaskScheduler(workflowTaskScheduler);
        registry.setDagScheduler(dagScheduler);
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
                                          WorkflowConfigProperties config,
                                          DagScheduler dagScheduler,
                                          WorkflowEventRecorder eventRecorder) {
        log.info("工作流执行引擎初始化完成");
        return new WorkflowEngine(registry, stepExecutor, expressionEngine, repository,
                config, dagScheduler, eventRecorder);
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
    public WorkflowRunner workflowRunner(WorkflowEngine engine) {
        return new WorkflowRunner(engine);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowCommandService workflowCommandService(WorkflowRegistry registry,
                                                          WorkflowRepository repository,
                                                          WorkflowRunner runner,
                                                          WorkflowEventRecorder eventRecorder) {
        return new WorkflowCommandService(registry, repository, runner, eventRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public WakeupScheduler wakeupScheduler(WorkflowRepository repository,
                                            WorkflowRunner runner,
                                            WorkflowEventRecorder eventRecorder) {
        return new WakeupScheduler(repository, runner, eventRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowTriggerManager workflowTriggerManager(WorkflowCommandService commandService,
                                                          WorkflowRegistry registry,
                                                          WorkflowRepository repository,
                                                          TaskScheduler workflowTaskScheduler) {
        return new WorkflowTriggerManager(commandService, registry, repository, workflowTaskScheduler);
    }

    /**
     * 应用启动完成后依次执行：内置工作流释放、崩溃恢复、触发器注册、YAML 热加载和唤醒调度。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        var config = ctx.getBean(WorkflowConfigProperties.class);

        // 延迟注入 triggerManager 到 registry，避免循环依赖
        ctx.getBean(WorkflowRegistry.class).setTriggerManager(ctx.getBean(WorkflowTriggerManager.class));

        // ① 释放内置工作流到用户目录（在崩溃恢复和热加载之前）
        seedBuiltinWorkflows(config);

        // ② 崩溃恢复
        ctx.getBean(WorkflowEngine.class).recoverInterruptedInstances();

        // ③ 触发器注册
        ctx.getBean(WorkflowTriggerManager.class).registerAllTriggers();

        // ④ YAML 热加载（会扫描用户目录，发现刚释放的文件）
        ctx.getBean(WorkflowRegistry.class).startScheduledScan();

        // ⑤ 启动唤醒调度器定时扫描
        var wakeupScheduler = ctx.getBean(WakeupScheduler.class);
        var taskScheduler = ctx.getBean("workflowTaskScheduler", TaskScheduler.class);
        int wakeupInterval = config.getWakeup().getScanIntervalSeconds();
        taskScheduler.scheduleAtFixedRate(wakeupScheduler::scan, Duration.ofSeconds(wakeupInterval));
        log.info("唤醒调度器已启动: interval={}s", wakeupInterval);

        log.info("工作流崩溃恢复、触发器注册、YAML 热加载和唤醒调度已完成");
    }

    /**
     * 将 classpath 中的内置工作流模板释放到用户工作流目录。
     *
     * <p>仅当用户目录中不存在同名文件时才复制，不覆盖用户已修改的版本。
     * 释放后由已有的热加载扫描机制自动发现并注册。</p>
     */
    private void seedBuiltinWorkflows(WorkflowConfigProperties config) {
        if (!config.isSeedBuiltinWorkflows()) {
            log.debug("内置工作流释放已禁用");
            return;
        }

        // 解析用户工作流目录路径（处理 ~ 前缀）
        String dirPath = config.getDefinitionsDir();
        if (dirPath.startsWith("~")) {
            dirPath = System.getProperty("user.home") + dirPath.substring(1);
        }
        Path targetDir = Path.of(dirPath);

        // 确保目录存在
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            log.warn("创建工作流目录失败: path={}, error={}", targetDir, e.getMessage());
            return;
        }

        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:builtin-workflows/*.yml");
            int seeded = 0;

            for (Resource resource : resources) {
                try {
                    String filename = resource.getFilename();
                    if (filename == null) {
                        continue;
                    }
                    Path targetFile = targetDir.resolve(filename);

                    if (Files.exists(targetFile)) {
                        log.debug("内置工作流已存在，跳过: file={}", filename);
                        continue;
                    }

                    try (var in = resource.getInputStream()) {
                        Files.copy(in, targetFile);
                        seeded++;
                        log.info("内置工作流已释放: file={}", filename);
                    }
                } catch (IOException e) {
                    log.warn("内置工作流释放失败: file={}, error={}",
                            resource.getFilename(), e.getMessage());
                }
            }

            if (seeded > 0) {
                log.info("内置工作流释放完成: 新增={}", seeded);
            }
        } catch (IOException e) {
            log.debug("内置工作流资源目录不存在或为空: error={}", e.getMessage());
        }
    }
}
