package com.lifepilot.skill.config;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.audit.SkillAuditRepository;
import com.lifepilot.skill.hub.SkillHubClient;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.markdown.SkillFileWatcher;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillEmbeddingCacheRepository;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.skill.tool.SkillLoadTool;
import com.lifepilot.skill.tool.SkillLoadToolExecutor;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.tool.BuiltinTool;
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
 * 注册 Skill 框架核心组件、Markdown 解析与热加载、审计追溯。
 * 每个 Bean 使用 {@link ConditionalOnMissingBean} 允许用户覆盖。</p>
 *
 * <p>Phase B.3 后：{@link MarkdownSkillParser}（{@code com.lifepilot.skill}）已接入，
 * {@link MarkdownSkillLoader} / {@link SkillFileWatcher} 改用新 parser + 两道 validator
 * （{@link SkillDescriptionValidator} / {@link SkillBodyValidator}），
 * BUILTIN 来源由 {@code SkillDiscoveryRegistrar}（meta 模块）经 {@code SkillInstaller} 安装。
 * REST Controller 的 Skill 创建/更新端点待 Phase B.6 接回。
 * SkillGenerator / SkillGapDetector / SkillGenerationTool 等 AUTO_GENERATED 相关 Bean 待 Phase B.5 恢复。</p>
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
                                         SkillInstallationRepository installationRepository,
                                         SkillMetricsTracker skillMetricsTracker,
                                         ApplicationEventPublisher eventPublisher) {
        log.info("Skill 系统: 注册 SkillActivator");
        return new SkillActivator(skillRegistry, installationRepository, skillMetricsTracker, eventPublisher);
    }

    // ==================== skill.load BuiltinTool（统一激活入口） ====================

    /**
     * {@code skill.load} 执行器 —— 负责参数校验 + 委托 SkillActivator 完成激活。
     */
    @Bean
    @ConditionalOnMissingBean
    public SkillLoadToolExecutor skillLoadToolExecutor(SkillActivator skillActivator,
                                                       SkillInstallationRepository installationRepository) {
        log.info("Skill 系统: 注册 SkillLoadToolExecutor");
        return new SkillLoadToolExecutor(skillActivator, installationRepository);
    }

    /**
     * {@code skill.load} BuiltinTool 定义载体（持有 executor，提供工具元数据）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SkillLoadTool skillLoadTool(SkillLoadToolExecutor skillLoadToolExecutor) {
        log.info("Skill 系统: 注册 SkillLoadTool");
        return new SkillLoadTool(skillLoadToolExecutor);
    }

    /**
     * 将 {@code skill.load} 暴露为 {@link BuiltinTool} Bean —— 由
     * {@link com.lifepilot.tool.registry.BuiltinToolRegistrar} 在 ApplicationReady 时
     * 自动校验并注册到 {@link DynamicToolRegistry}，进入 Tier 1 pinned 白名单后常驻 prompt。
     */
    @Bean
    public BuiltinTool skillLoadBuiltin(SkillLoadTool skillLoadTool) {
        return skillLoadTool.tool();
    }

    // ==================== Markdown 解析与热加载 ====================

    // MarkdownSkillParser 通过 @Component 自动注册（无条件），避免在 skills.enabled=false
    // 的测试/生产配置下让 SkillInstaller @Service 拿不到依赖。

    @Bean
    @ConditionalOnMissingBean
    public MarkdownSkillSerializer markdownSkillSerializer() {
        log.info("Skill 系统: 注册 MarkdownSkillSerializer");
        return new MarkdownSkillSerializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownSkillLoader markdownSkillLoader(SkillRegistry registry,
                                                   SkillConfigProperties config,
                                                   MarkdownSkillParser parser,
                                                   SkillDescriptionValidator descriptionValidator,
                                                   SkillBodyValidator bodyValidator) {
        log.info("Skill 系统: 注册 MarkdownSkillLoader, directory={}", config.getDirectory());
        return new MarkdownSkillLoader(registry, config, parser, descriptionValidator, bodyValidator);
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
     * 应用启动完成后触发 Markdown Skill 初始加载和文件监听启动。
     *
     * <p>{@link SkillFileWatcher#start()} 内部会调用 {@link MarkdownSkillLoader#loadAll()} 完成初始加载，
     * 然后启动 WatchService 监听文件变更。</p>
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
        // TODO Phase B.5: SkillGenerationTool 接入新 SkillSynthesizer 后在此重新启用
    }
}
