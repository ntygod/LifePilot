package com.lifepilot.skill.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.audit.SkillAuditRepository;
import com.lifepilot.skill.bridge.SkillToToolBridge;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.builtin.BuiltinSkillRegistrar;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.habit.HabitSkillProvider;
import com.lifepilot.skill.builtin.memory.MemorySkillProvider;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleSkillProvider;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.skill.builtin.todo.TodoSkillProvider;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.skill.generation.SkillGapDetector;
import com.lifepilot.skill.generation.SkillGenerator;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.markdown.SkillFileWatcher;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.skill.validation.FormatValidator;
import com.lifepilot.skill.validation.SandboxValidator;
import com.lifepilot.skill.validation.SecurityValidator;
import com.lifepilot.skill.validation.SkillValidationPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Skill 系统 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.skills.enabled=true}（默认）激活，
 * 注册 Skill 框架核心组件、Markdown 解析与热加载、安全验证管线、
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
    public SkillSearchIndex skillSearchIndex(@Autowired(required = false) LlmRouter llmRouter) {
        if (llmRouter == null) {
            log.warn("Skill 系统: LlmRouter 不可用，SkillSearchIndex 降级为关键词匹配模式");
        } else {
            log.info("Skill 系统: 注册 SkillSearchIndex（向量搜索模式）");
        }
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
    public SkillActivator skillActivator(SkillRegistry skillRegistry,
                                         SkillMetricsTracker skillMetricsTracker,
                                         ApplicationEventPublisher eventPublisher) {
        log.info("Skill 系统: 注册 SkillActivator");
        return new SkillActivator(skillRegistry, skillMetricsTracker, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillToToolBridge skillToToolBridge(DynamicToolRegistry toolRegistry,
                                              SkillRegistry skillRegistry,
                                              SkillActivator skillActivator) {
        log.info("Skill 系统: 注册 SkillToToolBridge");
        return new SkillToToolBridge(toolRegistry, skillRegistry, skillActivator);
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
    public TodoSkillProvider todoSkillProvider(TodoRepository todoRepository,
                                               PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 TodoSkillProvider");
        return new TodoSkillProvider(todoRepository, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleSkillProvider scheduleSkillProvider(ScheduleRepository scheduleRepository,
                                                       PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 ScheduleSkillProvider");
        return new ScheduleSkillProvider(scheduleRepository, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public HabitSkillProvider habitSkillProvider(HabitRepository habitRepository,
                                                 PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 HabitSkillProvider");
        return new HabitSkillProvider(habitRepository, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({HybridRetriever.class, SemanticMemory.class})
    public MemorySkillProvider memorySkillProvider(HybridRetriever hybridRetriever,
                                                   SemanticMemory semanticMemory,
                                                   PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 MemorySkillProvider（记忆系统已就绪）");
        return new MemorySkillProvider(hybridRetriever, semanticMemory, promptRegistry);
    }

    // --- 注册器 ---

    @Bean
    @ConditionalOnMissingBean
    public BuiltinSkillRegistrar builtinSkillRegistrar(List<BuiltinSkillProvider> providers,
                                                       SkillRegistry skillRegistry,
                                                       DynamicToolRegistry toolRegistry,
                                                       SkillConfigProperties skillConfigProperties) {
        log.info("Skill 系统: 注册 BuiltinSkillRegistrar, providers={}", providers.size());
        return new BuiltinSkillRegistrar(providers, skillRegistry, toolRegistry, skillConfigProperties);
    }

    // ==================== Markdown 解析与热加载 ====================

    @Bean
    @ConditionalOnMissingBean
    public MarkdownSkillParser markdownSkillParser() {
        log.info("Skill 系统: 注册 MarkdownSkillParser");
        return new MarkdownSkillParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownSkillSerializer markdownSkillSerializer() {
        log.info("Skill 系统: 注册 MarkdownSkillSerializer");
        return new MarkdownSkillSerializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownSkillLoader markdownSkillLoader(MarkdownSkillParser parser,
                                                   SkillRegistry registry,
                                                   SkillConfigProperties config) {
        log.info("Skill 系统: 注册 MarkdownSkillLoader, directory={}", config.getDirectory());
        return new MarkdownSkillLoader(parser, registry, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillFileWatcher skillFileWatcher(MarkdownSkillLoader loader,
                                            SkillRegistry registry,
                                            SkillConfigProperties config) {
        log.info("Skill 系统: 注册 SkillFileWatcher");
        return new SkillFileWatcher(loader, registry, config);
    }

    // ==================== 安全验证管线 ====================

    @Bean
    @ConditionalOnMissingBean
    public FormatValidator formatValidator(MarkdownSkillParser markdownParser) {
        log.info("Skill 系统: 注册 FormatValidator");
        return new FormatValidator(markdownParser);
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityValidator securityValidator(DynamicToolRegistry toolRegistry) {
        log.info("Skill 系统: 注册 SecurityValidator");
        return new SecurityValidator(toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SandboxValidator sandboxValidator(MarkdownSkillParser markdownParser) {
        log.info("Skill 系统: 注册 SandboxValidator");
        return new SandboxValidator(markdownParser);
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
                                            SkillConfigProperties config,
                                            PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 SkillGapDetector, gapThreshold={}",
                config.getAutoGeneration().getGapThreshold());
        return new SkillGapDetector(registry, llmRouter, config, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public SkillGenerator skillGenerator(LlmRouter llmRouter,
                                         SkillValidationPipeline pipeline,
                                         MarkdownSkillParser markdownParser,
                                         MarkdownSkillSerializer markdownSerializer,
                                         SkillRegistry registry,
                                         SkillConfigProperties config,
                                         PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 SkillGenerator");
        return new SkillGenerator(llmRouter, pipeline, markdownParser, markdownSerializer, registry, config, promptRegistry);
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
     * 应用启动完成后触发 Markdown Skill 初始加载、文件监听启动和统一 skills 工具注册。
     *
     * <p>{@link SkillFileWatcher#start()} 内部会调用 {@link MarkdownSkillLoader#loadAll()} 完成初始加载，
     * 然后启动 WatchService 监听文件变更。之后调用 {@link SkillToToolBridge#registerSkillsTool()}
     * 注册统一的 skills 工具到 DynamicToolRegistry。</p>
     *
     * <p>使用 {@code @Order(Ordered.LOWEST_PRECEDENCE)} 确保在
     * {@link BuiltinSkillRegistrar#registerAll()} 之后执行，
     * 保证 Builtin 工具（如 {@code builtin.shell.exec}）已注册到 DynamicToolRegistry，
     * 用户 Skill 的 suggestedTools 校验才能通过。</p>
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();

        // SkillFileWatcher.start() 内部已包含 MarkdownSkillLoader.loadAll() 初始加载
        if (ctx.containsBean("skillFileWatcher")) {
            var watcher = ctx.getBean(SkillFileWatcher.class);
            watcher.start();
            log.info("ApplicationReady: SkillFileWatcher 已启动（含初始加载）");
        }

        // 注册统一 skills 工具
        if (ctx.containsBean("skillToToolBridge")) {
            ctx.getBean(SkillToToolBridge.class).registerSkillsTool();
            log.info("ApplicationReady: 统一 skills 工具已注册");
        }
    }
}
