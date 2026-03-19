package com.lifepilot.meta.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.meta.convenience.CapabilityAggregator;
import com.lifepilot.meta.convenience.IntrospectionSkillProvider;
import com.lifepilot.meta.convenience.SkillDiscoveryRegistrar;
import com.lifepilot.meta.infra.InfraToolProvider;
import com.lifepilot.meta.infra.memory.MemoryToolProvider;
import com.lifepilot.meta.infra.storage.StorageToolProvider;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.meta.infra.browser.BrowserSessionManager;
import com.lifepilot.meta.infra.interaction.CliInteractionHandler;
import com.lifepilot.meta.infra.interaction.InteractionBridge;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.sandbox.booter.SandboxBooter;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.engine.WorkflowCommandService;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import jakarta.annotation.Nullable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 元能力系统 Spring Boot 自动配置。
 *
 * <p>注册元能力模块所有核心 Bean：InfraToolProvider、BrowserSessionManager、
 * InteractionBridge、CapabilityAggregator、IntrospectionSkillProvider、
 * SkillDiscoveryRegistrar。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
@AutoConfiguration
@EnableConfigurationProperties(MetaProperties.class)
public class MetaAutoConfiguration {

    /**
     * 注册交互桥接器 — 管理 Agent 与用户之间的交互请求/响应生命周期。
     *
     * <p>SseSessionManager 为可选依赖（Web Channel），CliInteractionHandler 为可选依赖（CLI Channel）。</p>
     */
    @Bean
    InteractionBridge interactionBridge(MetaProperties properties,
                                        @Nullable SseSessionManager sseSessionManager,
                                        @Nullable CliInteractionHandler cliInteractionHandler) {
        return new InteractionBridge(properties, sseSessionManager, cliInteractionHandler);
    }

    /**
     * 注册基础工具提供者。
     *
     * <p>SandboxBooter、CodeValidator、SandboxRepository 为可选依赖，仅在沙箱模块可用时注入。
     * InteractionBridge 注入交互桥接器。
     * BrowserSessionManager 为可选依赖，仅在 Playwright 可用时注入。
     * NotificationService 注入统一通知服务，供 notify 工具使用。</p>
     */
    @Bean
    InfraToolProvider infraToolProvider(MetaProperties properties,
                                        RestClient.Builder restClientBuilder,
                                        @Nullable SandboxBooter sandboxBooter,
                                        @Nullable CodeValidator codeValidator,
                                        @Nullable SandboxRepository sandboxRepository,
                                        @Nullable InteractionBridge interactionBridge,
                                        @Nullable BrowserSessionManager browserSessionManager,
                                        @Nullable NotificationService notificationService,
                                        @Nullable WorkflowRegistry workflowRegistry,
                                        @Nullable WorkflowCommandService workflowCommandService,
                                        @Nullable CronTaskRepository cronTaskRepository,
                                        @Nullable CronScheduler cronScheduler,
                                        @Nullable AgentConfigProperties agentConfigProperties) {
        return new InfraToolProvider(properties, restClientBuilder, sandboxBooter, codeValidator, sandboxRepository, interactionBridge, browserSessionManager, notificationService, workflowRegistry, workflowCommandService, cronTaskRepository, cronScheduler, agentConfigProperties);
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
     * 注册系统自省 Skill 提供者 — 注册 5 个自省工具。
     *
     * <p>WorkflowRepository 和 McpServerRegistry 为可选依赖，
     * 用于 system.runtime 工具查询运行时动态信息。</p>
     */
    @Bean
    IntrospectionSkillProvider introspectionSkillProvider(CapabilityAggregator aggregator,
                                                          SkillRegistry skillRegistry,
                                                          AgentRegistry agentRegistry,
                                                          DynamicToolRegistry toolRegistry,
                                                          WorkflowRegistry workflowRegistry,
                                                          @Nullable WorkflowRepository workflowRepository,
                                                          @Nullable McpServerRegistry mcpServerRegistry) {
        return new IntrospectionSkillProvider(aggregator, skillRegistry,
                agentRegistry, toolRegistry, workflowRegistry,
                workflowRepository, mcpServerRegistry);
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
     * 注册存储工具提供者 — 注册 7 个数据存储 CRUD 工具。
     *
     * <p>依赖 DataStoreManager（来自 datastore 模块）和 PromptRegistry。</p>
     */
    @Bean
    StorageToolProvider storageToolProvider(DataStoreManager dataStoreManager,
                                           PromptRegistry promptRegistry) {
        return new StorageToolProvider(dataStoreManager, promptRegistry);
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
                                          @Nullable MemoryProperties memoryProperties) {
        return new MemoryToolProvider(hybridRetriever, semanticMemory,
                episodicMemory, documentRetriever, sessionKbRepo, memoryProperties);
    }
}
