package com.lifepilot.skill.config;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.skill.action.*;
import com.lifepilot.skill.activation.SkillLifecycleManager;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.activation.SubAgentFactory;
import com.lifepilot.skill.audit.SkillAuditRepository;
import com.lifepilot.skill.bridge.SkillToToolBridge;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.builtin.BuiltinSkillRegistrar;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.habit.HabitSkillProvider;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleSkillProvider;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.skill.builtin.todo.TodoSkillProvider;
import com.lifepilot.skill.generation.SkillGapDetector;
import com.lifepilot.skill.generation.SkillGenerator;
import com.lifepilot.skill.memory.MemoryAccessEnforcer;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.skill.validation.FormatValidator;
import com.lifepilot.skill.validation.SandboxValidator;
import com.lifepilot.skill.validation.SecurityValidator;
import com.lifepilot.skill.validation.SkillValidationPipeline;
import com.lifepilot.skill.yaml.SkillFileWatcher;
import com.lifepilot.skill.yaml.YamlSchemaValidator;
import com.lifepilot.skill.yaml.YamlSkillLoader;
import com.lifepilot.skill.yaml.YamlSkillSerializer;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Skill 系统 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.skills.enabled=true}（默认）激活，
 * 注册 Skill 框架核心组件、YAML 解析与热加载、动作执行器、安全验证管线、
 * Skill 自扩展、审计追溯和内置 Skill 提供者。
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
    public SkillDefinitionValidator skillDefinitionValidator(DynamicToolRegistry toolRegistry,
                                                             SkillConfigProperties skillConfig) {
        log.info("Skill 系统: 注册 SkillDefinitionValidator");
        return new SkillDefinitionValidator(toolRegistry, skillConfig);
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
                                       ApplicationEventPublisher eventPublisher,
                                       SkillConfigProperties skillConfig) {
        log.info("Skill 系统: 注册 SkillRegistry");
        return new SkillRegistry(validator, searchIndex, eventPublisher, skillConfig);
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

    // ==================== YAML 解析与热加载 ====================

    @Bean
    @ConditionalOnMissingBean
    public YamlSchemaValidator yamlSchemaValidator(SkillConfigProperties config) {
        log.info("Skill 系统: 注册 YamlSchemaValidator");
        return new YamlSchemaValidator(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public YamlSkillLoader yamlSkillLoader(YamlSchemaValidator validator,
                                           SkillRegistry registry,
                                           SkillConfigProperties config) {
        log.info("Skill 系统: 注册 YamlSkillLoader, directory={}", config.getDirectory());
        return new YamlSkillLoader(validator, registry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public YamlSkillSerializer yamlSkillSerializer() {
        log.info("Skill 系统: 注册 YamlSkillSerializer");
        return new YamlSkillSerializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillFileWatcher skillFileWatcher(YamlSkillLoader loader,
                                            SkillRegistry registry,
                                            SkillConfigProperties config) {
        log.info("Skill 系统: 注册 SkillFileWatcher");
        return new SkillFileWatcher(loader, registry, config);
    }

    // ==================== 动作执行器 ====================

    @Bean
    @ConditionalOnMissingBean
    public VariableResolver variableResolver() {
        log.info("Skill 系统: 注册 VariableResolver");
        return new VariableResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public DangerousCommandDetector dangerousCommandDetector() {
        log.info("Skill 系统: 注册 DangerousCommandDetector");
        return new DangerousCommandDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    public HttpActionExecutor httpActionExecutor(RestClient.Builder restClientBuilder,
                                                VariableResolver resolver,
                                                SkillConfigProperties config) {
        log.info("Skill 系统: 注册 HttpActionExecutor, ssrfProtection={}",
                config.getHttpAction().isSsrfProtectionEnabled());
        return new HttpActionExecutor(restClientBuilder.build(), resolver, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ShellActionExecutor shellActionExecutor(DangerousCommandDetector detector,
                                                  VariableResolver resolver,
                                                  SkillConfigProperties config) {
        log.info("Skill 系统: 注册 ShellActionExecutor, maxTimeout={}s",
                config.getShellAction().getMaxTimeoutSeconds());
        return new ShellActionExecutor(detector, resolver, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChainActionExecutor chainActionExecutor(SubAgentFactory factory,
                                                   SkillRegistry registry,
                                                   VariableResolver resolver,
                                                   SkillConfigProperties config) {
        log.info("Skill 系统: 注册 ChainActionExecutor, maxSteps={}",
                config.getChainAction().getMaxSteps());
        return new ChainActionExecutor(factory, registry, resolver, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateActionExecutor templateActionExecutor(VariableResolver resolver) {
        log.info("Skill 系统: 注册 TemplateActionExecutor");
        return new TemplateActionExecutor(resolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillActionDispatcher skillActionDispatcher(HttpActionExecutor http,
                                                      ShellActionExecutor shell,
                                                      ChainActionExecutor chain,
                                                      TemplateActionExecutor template,
                                                      VariableResolver resolver) {
        log.info("Skill 系统: 注册 SkillActionDispatcher");
        return new SkillActionDispatcher(http, shell, chain, template, resolver);
    }

    // ==================== 安全验证管线 ====================

    @Bean
    @ConditionalOnMissingBean
    public FormatValidator formatValidator(YamlSchemaValidator schemaValidator) {
        log.info("Skill 系统: 注册 FormatValidator");
        return new FormatValidator(schemaValidator);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityValidator securityValidator(DynamicToolRegistry toolRegistry,
                                              SkillConfigProperties config) {
        log.info("Skill 系统: 注册 SecurityValidator");
        return new SecurityValidator(toolRegistry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SandboxValidator sandboxValidator() {
        log.info("Skill 系统: 注册 SandboxValidator");
        return new SandboxValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillValidationPipeline skillValidationPipeline(FormatValidator format,
                                                           SecurityValidator security,
                                                           SandboxValidator sandbox) {
        log.info("Skill 系统: 注册 SkillValidationPipeline");
        return new SkillValidationPipeline(format, security, sandbox);
    }

    // ==================== Skill 自扩展（条件装配） ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public SkillGapDetector skillGapDetector(SkillRegistry registry,
                                            LlmRouter llmRouter,
                                            SkillConfigProperties config) {
        log.info("Skill 系统: 注册 SkillGapDetector, gapThreshold={}",
                config.getAutoGeneration().getGapThreshold());
        return new SkillGapDetector(registry, llmRouter, config);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public SkillGenerator skillGenerator(LlmRouter llmRouter,
                                         SkillValidationPipeline pipeline,
                                         YamlSkillLoader loader,
                                         YamlSkillSerializer serializer,
                                         SkillRegistry registry,
                                         SkillConfigProperties config) {
        log.info("Skill 系统: 注册 SkillGenerator");
        return new SkillGenerator(llmRouter, pipeline, loader, serializer, registry, config);
    }

    // ==================== 审计 ====================

    @Bean
    @ConditionalOnMissingBean
    public SkillAuditRepository skillAuditRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 系统: 注册 SkillAuditRepository");
        return new SkillAuditRepository(jdbcTemplate);
    }

    // ==================== 启动后初始化 ====================

    /**
     * 应用启动完成后触发 YAML Skill 初始加载和文件监听启动。
     *
     * <p>{@link SkillFileWatcher#start()} 内部会调用 {@link YamlSkillLoader#loadAll()} 完成初始加载，
     * 然后启动 WatchService 监听文件变更。</p>
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();

        // SkillFileWatcher.start() 内部已包含 YamlSkillLoader.loadAll() 初始加载
        if (ctx.containsBean("skillFileWatcher")) {
            var watcher = ctx.getBean(SkillFileWatcher.class);
            watcher.start();
            log.info("ApplicationReady: SkillFileWatcher 已启动（含初始加载）");
        }
    }
}
