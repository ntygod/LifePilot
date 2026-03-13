package com.lifepilot.workflow.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.engine.DagScheduler;
import com.lifepilot.workflow.engine.StepExecutor;
import com.lifepilot.workflow.engine.WakeupScheduler;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.engine.WorkflowEventRecorder;
import com.lifepilot.workflow.engine.WorkflowRealtimeEventHub;
import com.lifepilot.workflow.engine.WorkflowRunner;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.Result;
import com.lifepilot.workflow.model.WorkflowDefinition;
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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workflow engine auto-configuration.
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
                                                 WorkflowYamlParser yamlParser,
                                                 WorkflowRealtimeEventHub realtimeEventHub) {
        return new WorkflowRepository(jdbcTemplate, yamlParser, realtimeEventHub);
    }

    @Bean
    @ConditionalOnMissingBean
    public DagScheduler dagScheduler() {
        return new DagScheduler();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRealtimeEventHub workflowRealtimeEventHub() {
        return new WorkflowRealtimeEventHub();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEventRecorder workflowEventRecorder(JdbcTemplate jdbcTemplate,
                                                       WorkflowConfigProperties config,
                                                       WorkflowRealtimeEventHub realtimeEventHub) {
        return new WorkflowEventRecorder(jdbcTemplate, config, realtimeEventHub);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry(WorkflowYamlParser parser,
                                             WorkflowConfigProperties config,
                                             TaskScheduler workflowTaskScheduler,
                                             DagScheduler dagScheduler,
                                             DynamicToolRegistry toolRegistry,
                                             SkillRegistry skillRegistry) {
        var registry = new WorkflowRegistry(parser);
        registry.setConfigProperties(config);
        registry.setTaskScheduler(workflowTaskScheduler);
        registry.setDagScheduler(dagScheduler);
        registry.setToolRegistry(toolRegistry);
        registry.setSkillRegistry(skillRegistry);
        log.info("工作流注册中心初始化完成");
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public StepExecutor workflowStepExecutor(SkillRegistry skillRegistry,
                                             SkillActivator skillActivator,
                                             DynamicToolRegistry toolRegistry,
                                             LlmRouter llmRouter,
                                             MultimodalRouter multimodalRouter,
                                             WorkflowConfigProperties config,
                                             NotificationService notificationService) {
        return new StepExecutor(skillRegistry, skillActivator, toolRegistry, llmRouter, multimodalRouter, config, notificationService);
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
                                                         WorkflowEventRecorder eventRecorder,
                                                         ExpressionEngine expressionEngine) {
        return new WorkflowCommandService(registry, repository, runner, eventRecorder, expressionEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public WakeupScheduler wakeupScheduler(WorkflowRepository repository,
                                           WorkflowRunner runner,
                                           WorkflowEventRecorder eventRecorder,
                                           WorkflowEngine engine) {
        return new WakeupScheduler(repository, runner, eventRecorder, engine);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowTriggerManager workflowTriggerManager(WorkflowCommandService commandService,
                                                         WorkflowRegistry registry,
                                                         WorkflowRepository repository,
                                                         TaskScheduler workflowTaskScheduler) {
        return new WorkflowTriggerManager(commandService, registry, repository, workflowTaskScheduler);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        var config = ctx.getBean(WorkflowConfigProperties.class);
        var parser = ctx.getBean(WorkflowYamlParser.class);
        var registry = ctx.getBean(WorkflowRegistry.class);

        registry.setTriggerManager(ctx.getBean(WorkflowTriggerManager.class));

        registry.startScheduledScan();
        loadBuiltinWorkflows(parser, registry, localWorkflowStems(config));
        ctx.getBean(WorkflowTriggerManager.class).registerAllTriggers();
        registry.setStartupPhase(false);
        ctx.getBean(WorkflowEngine.class).recoverInterruptedInstances();

        var wakeupScheduler = ctx.getBean(WakeupScheduler.class);
        var taskScheduler = ctx.getBean("workflowTaskScheduler", TaskScheduler.class);
        int wakeupInterval = config.getWakeup().getScanIntervalSeconds();
        taskScheduler.scheduleAtFixedRate(wakeupScheduler::scan, Duration.ofSeconds(wakeupInterval));
        log.info("唤醒调度器已启动: interval={}s", wakeupInterval);
        log.info("工作流启动序列完成: 本地热加载 -> 内置补齐 -> 触发器注册 -> 崩溃恢复 -> 唤醒调度");
    }

    private void loadBuiltinWorkflows(WorkflowYamlParser parser,
                                      WorkflowRegistry registry,
                                      Set<String> localWorkflowStems) {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:builtin-workflows/*.yml");
            int loaded = 0;
            int skipped = 0;

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String fileStem = fileStem(filename);
                if (localWorkflowStems.contains(fileStem)) {
                    skipped++;
                    log.info("内置工作流检测到本地同名文件，跳过程序包版本: file={}", filename);
                    continue;
                }
                try {
                    String yaml = resource.getContentAsString(StandardCharsets.UTF_8);
                    Result<WorkflowDefinition, List<String>> result = parser.parse(yaml);
                    switch (result) {
                        case Result.Ok<WorkflowDefinition, List<String>> ok -> {
                            if (registry.find(ok.value().id()).isPresent()) {
                                skipped++;
                                log.info("内置工作流检测到本地已加载同 ID 定义，跳过程序包版本: file={}, id={}",
                                        filename, ok.value().id());
                                continue;
                            }
                            if (registry.registerBuiltin(ok.value(), filename)) {
                                loaded++;
                                log.info("内置工作流已加载: file={}, id={}", filename, ok.value().id());
                            }
                        }
                        case Result.Err<WorkflowDefinition, List<String>> err ->
                                log.warn("内置工作流解析失败，已跳过: file={}, errors={}", filename, err.error());
                    }
                } catch (IOException e) {
                    log.warn("读取内置工作流失败: file={}, error={}", filename, e.getMessage());
                }
            }

            log.info("内置工作流加载完成: loaded={}, skipped={}", loaded, skipped);
        } catch (IOException e) {
            log.debug("未找到内置工作流资源目录: error={}", e.getMessage());
        }
    }

    private Set<String> localWorkflowStems(WorkflowConfigProperties config) {
        Path definitionsDir = WorkflowRegistry.resolveDefinitionsDir(config.getDefinitionsDir());
        if (!Files.isDirectory(definitionsDir)) {
            return Set.of();
        }
        Set<String> stems = new HashSet<>();
        try (var files = Files.list(definitionsDir)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml") || name.endsWith(".yaml"))
                    .map(this::fileStem)
                    .forEach(stems::add);
        } catch (IOException e) {
            log.warn("读取本地工作流目录失败，将继续加载内置工作流: dir={}, error={}", definitionsDir, e.getMessage());
            return Set.of();
        }
        return Set.copyOf(stems);
    }

    private String fileStem(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
