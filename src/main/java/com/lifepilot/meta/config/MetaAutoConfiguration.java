package com.lifepilot.meta.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.meta.convenience.CapabilityAggregator;
import com.lifepilot.meta.convenience.IntrospectionToolProvider;
import com.lifepilot.meta.convenience.SkillDiscoveryRegistrar;
import com.lifepilot.meta.infra.InfraToolProvider;
import com.lifepilot.meta.infra.memory.MemoryToolProvider;
import com.lifepilot.meta.infra.storage.StorageToolProvider;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.shell.BackgroundProcessManager;
import com.lifepilot.meta.infra.web.WebSearchConfigProvider;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 元能力系统 Spring Boot 自动配置。
 *
 * <p>注册元能力模块所有核心 Bean：InfraToolProvider、BrowserSessionManager、
 * CapabilityAggregator、IntrospectionToolProvider、SkillDiscoveryRegistrar。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.meta.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MetaProperties.class)
public class MetaAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MetaAutoConfiguration.class);

    /**
     * 注册后台进程管理器 — 管理通过 shell.exec(background=true) 启动的长时间运行进程。
     */
    @Bean
    BackgroundProcessManager backgroundProcessManager(MetaProperties properties,
                                                       @Nullable ApplicationEventPublisher eventPublisher) {
        return new BackgroundProcessManager(properties.getInfra().getProcess(), eventPublisher);
    }

    /**
     * 注册联网搜索配置提供者。
     */
    @Bean
    WebSearchConfigProvider webSearchConfigProvider(@Nullable UserSettingsRepository userSettingsRepository,
                                                    MetaProperties properties,
                                                    ObjectMapper objectMapper) {
        java.util.function.Supplier<String> dbReader = userSettingsRepository != null
                ? userSettingsRepository::getSearchConfig
                : () -> "{}";
        return new WebSearchConfigProvider(dbReader, properties.getInfra().getWebSearch(), objectMapper);
    }

    /**
     * 注册基础工具提供者。
     *
     * <p>SandboxSessionManager、CodeValidator、SandboxRepository 为可选依赖，仅在沙箱模块可用时注入。
     * BrowserSessionManager 为可选依赖，仅在 Playwright 可用时注入。
     * NotificationService 注入统一通知服务，供 notify 工具使用。</p>
     */
    @Bean
    InfraToolProvider infraToolProvider(MetaProperties properties,
                                        WebSearchConfigProvider webSearchConfigProvider,
                                        @Nullable SandboxSessionManager sandboxSessionManager,
                                        @Nullable CodeValidator codeValidator,
                                        @Nullable SandboxRepository sandboxRepository,
                                        @Nullable BrowserSessionManager browserSessionManager,
                                        @Nullable NotificationService notificationService,
                                        @Nullable WorkflowRegistry workflowRegistry,
                                        @Nullable WorkflowCommandService workflowCommandService,
                                        @Nullable CronTaskRepository cronTaskRepository,
                                        @Nullable CronScheduler cronScheduler,
                                        @Nullable NotificationProperties notificationProperties,
                                        @Nullable BackgroundProcessManager backgroundProcessManager,
                                        @Nullable ChannelRegistry channelRegistry,
                                        @Nullable ChannelOperationDispatcher channelOperationDispatcher,
                                        @Nullable ChannelDeliveryDispatcher channelDeliveryDispatcher,
                                        @Nullable ChannelInstanceService channelInstanceService,
                                        @Nullable com.lifepilot.skill.config.SkillConfigProperties skillConfigProperties,
                                        com.lifepilot.config.workspace.WorkspaceResolver workspaceResolver,
                                        @Nullable AttachmentRepository attachmentRepository) {
        String skillDir = skillConfigProperties != null ? skillConfigProperties.getDirectory() : null;
        return new InfraToolProvider(properties, webSearchConfigProvider, sandboxSessionManager, codeValidator, sandboxRepository, browserSessionManager, notificationService, workflowRegistry, workflowCommandService, cronTaskRepository, cronScheduler, notificationProperties, backgroundProcessManager, channelRegistry, channelOperationDispatcher, channelDeliveryDispatcher, channelInstanceService, skillDir, workspaceResolver, attachmentRepository);
    }

    /**
     * 注册浏览器会话管理器 — 仅在 Playwright 类可用时注册。
     */
    @Bean
    @ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
    BrowserSessionManager browserSessionManager(MetaProperties properties) {
        return new BrowserSessionManager(properties);
    }

    /**
     * 注册能力聚合器 — 从四个注册中心聚合系统能力信息。
     */
    @Bean
    CapabilityAggregator capabilityAggregator(SkillRegistry skillRegistry,
                                               AgentRegistry agentRegistry,
                                               DynamicToolRegistry toolRegistry,
                                               WorkflowRegistry workflowRegistry,
                                               MetaProperties properties,
                                               SharedScheduler sharedScheduler) {
        return new CapabilityAggregator(skillRegistry, agentRegistry,
                toolRegistry, workflowRegistry, properties, sharedScheduler);
    }

    /**
     * 注册系统自省工具提供者 — 注册 5 个自省工具。
     *
     * <p>WorkflowRepository 和 McpServerRegistry 为可选依赖，
     * 用于 system.runtime 工具查询运行时动态信息。</p>
     */
    @Bean
    IntrospectionToolProvider introspectionToolProvider(CapabilityAggregator aggregator,
                                                          DynamicToolRegistry toolRegistry,
                                                          WorkflowRegistry workflowRegistry,
                                                          @Nullable WorkflowRepository workflowRepository,
                                                          @Nullable McpServerRegistry mcpServerRegistry) {
        return new IntrospectionToolProvider(aggregator, toolRegistry,
                workflowRegistry, workflowRepository, mcpServerRegistry);
    }

    /**
     * 注册 find-skills Skill 提取器 — 启动时将内置 SKILL.md 提取到用户 Skill 目录。
     *
     * <p>通过 {@code lifepilot.meta.skill-discovery.enabled} 配置控制启用，默认 true。
     * 提取后由 MarkdownSkillLoader 在 ApplicationReadyEvent 时作为 UserDefined Skill 加载。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "lifepilot.meta.skill-discovery.enabled",
                           havingValue = "true", matchIfMissing = true)
    SkillDiscoveryRegistrar skillDiscoveryRegistrar(MetaProperties properties,
                                                    SkillConfigProperties skillConfig) {
        return new SkillDiscoveryRegistrar(properties, skillConfig);
    }

    /**
     * 注册存储工具提供者 — 注册 8 个数据存储 CRUD 工具。
     *
     * <p>依赖 DataStoreManager（来自 datastore 模块）。</p>
     */
    @Bean
    StorageToolProvider storageToolProvider(DataStoreManager dataStoreManager,
                                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new StorageToolProvider(dataStoreManager, objectMapper);
    }

    /**
     * 注册记忆管理工具提供者 — 注册 9 个记忆管理工具。
     *
     * <p>仅在 HybridRetriever 和 SemanticMemory Bean 可用时注册。
     * EpisodicMemory、DocumentRetriever、SessionKnowledgeBaseRepository、MemoryProperties 为可选依赖。</p>
     */
    @Bean
    @ConditionalOnBean({HybridRetriever.class, SemanticMemory.class})
    MemoryToolProvider memoryToolProvider(HybridRetriever hybridRetriever,
                                          SemanticMemory semanticMemory,
                                          @Nullable EpisodicMemory episodicMemory,
                                          @Nullable DocumentRetriever documentRetriever,
                                          @Nullable SessionKnowledgeBaseRepository sessionKbRepo,
                                          @Nullable SessionKnowledgeScopeResolver sessionKnowledgeScopeResolver,
                                          @Nullable MemoryProperties memoryProperties) {
        return new MemoryToolProvider(hybridRetriever, semanticMemory,
                episodicMemory, documentRetriever, sessionKbRepo, sessionKnowledgeScopeResolver, memoryProperties);
    }

    // ==================== 启动后工具注册 ====================

    /**
     * 应用启动完成后注册元能力模块所有工具到 DynamicToolRegistry。
     *
     * <p>使用 {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 确保在
     * SkillAutoConfiguration 的 Markdown Skill 加载之前完成工具注册，
     * 保证用户 Skill 的 suggestedTools 校验能通过。</p>
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void registerTools(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        var toolRegistry = ctx.getBean(DynamicToolRegistry.class);

        ctx.getBean(InfraToolProvider.class).registerTools(toolRegistry);
        ctx.getBean(IntrospectionToolProvider.class).registerTools(toolRegistry);
        ctx.getBean(StorageToolProvider.class).buildStorageTools().forEach(toolRegistry::registerBuiltinTool);
        if (ctx.containsBean("memoryToolProvider")) {
            ctx.getBean(MemoryToolProvider.class).registerTools(toolRegistry);
        }

        log.info("元能力模块工具注册完成");
    }
}
