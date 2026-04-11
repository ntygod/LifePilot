package com.lifepilot.skill.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.audit.SkillAuditRepository;
import com.lifepilot.skill.disclosure.SkillDisclosureTool;
import com.lifepilot.skill.disclosure.SkillGenerationTool;
import com.lifepilot.skill.hub.SkillHubClient;
import com.lifepilot.skill.generation.SkillGapDetector;
import com.lifepilot.skill.generation.SkillGenerator;
import com.lifepilot.skill.generation.SkillTemplateLibrary;
import com.lifepilot.skill.generation.ToolCapabilityManifest;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.markdown.SkillFileWatcher;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillEmbeddingCacheRepository;
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

/**
 * Skill 系统 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.skills.enabled=true}（默认）激活，
 * 注册 Skill 框架核心组件、Markdown 解析与热加载、安全验证管线、
 * Skill 自扩展和审计追溯。
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
    public SkillEmbeddingCacheRepository skillEmbeddingCacheRepository(JdbcTemplate jdbcTemplate) {
        return new SkillEmbeddingCacheRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillSearchIndex skillSearchIndex(@Autowired(required = false) EmbeddingRouter embeddingRouter,
                                             SkillEmbeddingCacheRepository cacheRepository) {
        if (embeddingRouter == null) {
            log.warn("Skill 系统: EmbeddingRouter 不可用，SkillSearchIndex 降级为关键词匹配模式");
        } else {
            log.info("Skill 系统: 注册 SkillSearchIndex（向量搜索模式，异步索引 + 持久化缓存）");
        }
        var asyncExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("skill-index-", 0).factory());
        return new SkillSearchIndex(embeddingRouter, asyncExecutor, cacheRepository);
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
                                         ApplicationEventPublisher eventPublisher,
                                         SkillConfigProperties config) {
        log.info("Skill 系统: 注册 SkillActivator");
        return new SkillActivator(skillRegistry, skillMetricsTracker, eventPublisher, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillDisclosureTool skillDisclosureTool(DynamicToolRegistry toolRegistry,
                                                   SkillActivator skillActivator,
                                                   SkillRegistry skillRegistry) {
        log.info("Skill 系统: 注册 SkillDisclosureTool（L1 搜索 + L2 加载）");
        return new SkillDisclosureTool(toolRegistry, skillActivator, skillRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public SkillGenerationTool skillGenerationTool(DynamicToolRegistry toolRegistry,
                                                   SkillGenerator skillGenerator) {
        log.info("Skill 系统: 注册 SkillGenerationTool（HIGH 风险）");
        return new SkillGenerationTool(toolRegistry, skillGenerator);
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
                                            SkillConfigProperties config,
                                            SharedScheduler sharedScheduler) {
        log.info("Skill 系统: 注册 SkillFileWatcher");
        return new SkillFileWatcher(loader, registry, config, sharedScheduler);
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
                                            GenerationRouter generationRouter,
                                            SkillConfigProperties config,
                                            PromptRegistry promptRegistry) {
        log.info("Skill 系统: 注册 SkillGapDetector, gapThreshold={}",
                config.getAutoGeneration().getGapThreshold());
        return new SkillGapDetector(registry, generationRouter, config, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolCapabilityManifest toolCapabilityManifest(DynamicToolRegistry toolRegistry) {
        log.info("Skill 系统: 注册 ToolCapabilityManifest");
        return new ToolCapabilityManifest(toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public SkillTemplateLibrary skillTemplateLibrary() {
        log.info("Skill 系统: 注册 SkillTemplateLibrary");
        return new SkillTemplateLibrary();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.auto-generation",
            name = "enabled", havingValue = "true", matchIfMissing = true)
    public SkillGenerator skillGenerator(GenerationRouter generationRouter,
                                         SkillValidationPipeline pipeline,
                                         MarkdownSkillParser markdownParser,
                                         MarkdownSkillSerializer markdownSerializer,
                                         SkillRegistry registry,
                                         SkillConfigProperties config,
                                         PromptRegistry promptRegistry,
                                         ToolCapabilityManifest toolCapabilityManifest,
                                         SkillTemplateLibrary skillTemplateLibrary) {
        log.info("Skill 系统: 注册 SkillGenerator（增强模式）");
        return new SkillGenerator(generationRouter, pipeline, markdownParser, markdownSerializer,
                registry, config, promptRegistry, toolCapabilityManifest, skillTemplateLibrary);
    }

    // ==================== 审计 ====================

    @Bean
    @ConditionalOnMissingBean
    public SkillAuditRepository skillAuditRepository(JdbcTemplate jdbcTemplate) {
        log.info("Skill 系统: 注册 SkillAuditRepository");
        return new SkillAuditRepository(jdbcTemplate);
    }

    // ==================== SkillHub ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.skills.skill-hub", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SkillHubClient skillHubClient(SkillConfigProperties skillConfig) {
        log.info("Skill 系统: 注册 SkillHubClient (CLI 模式)");
        return new SkillHubClient(skillConfig.getDirectory());
    }

    // ==================== 启动后初始化 ====================

    /**
     * 应用启动完成后触发 Markdown Skill 初始加载、文件监听启动和 L2 工具注册。
     *
     * <p>{@link SkillFileWatcher#start()} 内部会调用 {@link MarkdownSkillLoader#loadAll()} 完成初始加载，
     * 然后启动 WatchService 监听文件变更。之后注册 disclosure 和 generate_skill 工具。</p>
     *
     * <p>使用 {@code @Order(Ordered.LOWEST_PRECEDENCE - 1)} 确保在各 AutoConfiguration
     * 的 registerTools()（HIGHEST_PRECEDENCE）之后执行，
     * 保证工具已注册到 DynamicToolRegistry，用户 Skill 的 suggestedTools 校验才能通过。</p>
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();

        // SkillFileWatcher.start() 内部已包含 MarkdownSkillLoader.loadAll() 初始加载
        // 捕获异常避免 embedding 服务不可用时阻塞其他 ApplicationReadyEvent 监听器
        if (ctx.containsBean("skillFileWatcher")) {
            try {
                var watcher = ctx.getBean(SkillFileWatcher.class);
                watcher.start();
                log.info("ApplicationReady: SkillFileWatcher 已启动（含初始加载）");
            } catch (Exception e) {
                log.warn("ApplicationReady: SkillFileWatcher 启动失败，Skill 向量索引可能不可用: {}", e.getMessage());
            }
        }

        // 注册 L2 渐进式披露工具
        if (ctx.containsBean("skillDisclosureTool")) {
            ctx.getBean(SkillDisclosureTool.class).registerTools();
            log.debug("ApplicationReady: SkillDisclosureTool.registerTools() 已调用");
        }
        if (ctx.containsBean("skillGenerationTool")) {
            ctx.getBean(SkillGenerationTool.class).registerTools();
            log.info("ApplicationReady: generate_skill 工具已注册");
        }
    }
}
