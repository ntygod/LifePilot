package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.notification.PassiveNotificationQueue;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.conversation.DefaultConversationViewService;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.llm.config.LlmAutoConfiguration;
import com.lifepilot.tool.config.ToolAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 引擎 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.agent.enabled=true}（默认）激活，
 * 注册所有 Agent 核心 Bean，每个 Bean 使用
 * {@link ConditionalOnMissingBean} 允许用户覆盖。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
@AutoConfiguration(after = {ToolAutoConfiguration.class, LlmAutoConfiguration.class})
@EnableConfigurationProperties(AgentConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.agent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler contextAssembler(AgentConfigProperties config,
                                             PromptRegistry promptRegistry,
                                             @Autowired(required = false) WorkingMemory workingMemory,
                                             @Autowired(required = false) TokenBudgetAllocator tokenBudgetAllocator,
                                             @Autowired(required = false) DataRedactor dataRedactor,
                                             @Autowired(required = false) SemanticMemory semanticMemory,
                                             @Autowired(required = false) PassiveNotificationQueue passiveNotificationQueue,
                                             @Autowired(required = false) com.lifepilot.memory.config.MemoryProperties memoryProperties,
                                             @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
                                             @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker) {
        log.info("Agent 引擎: 注册 ContextAssembler（WorkingMemory {}，L3 语义记忆{}，L4 程序记忆{}）",
                workingMemory != null && tokenBudgetAllocator != null ? "完整模式" : "基础模式",
                semanticMemory != null ? "已启用" : "未启用",
                proceduralMemory != null ? "已启用" : "未启用");
        return new ContextAssembler(config, promptRegistry, workingMemory, tokenBudgetAllocator,
                dataRedactor, semanticMemory, passiveNotificationQueue, memoryProperties,
                proceduralMemory, effectivenessTracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(JdbcTemplate jdbcTemplate,
                                          ObjectMapper objectMapper,
                                          AgentConfigProperties config,
                                          @Autowired(required = false) com.lifepilot.memory.working.WorkingMemory workingMemory,
                                          SharedScheduler sharedScheduler) {
        var manager = new SessionManager(jdbcTemplate, objectMapper, config, workingMemory);
        // 通过 SharedScheduler 注册定时清理任务，替代 @Scheduled
        long intervalMs = config.getSession().getCleanupIntervalMs();
        sharedScheduler.cleanup().scheduleAtFixedRate(
                manager::cleanupExpiredSessions,
                intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("会话过期清理任务已注册: interval={}ms", intervalMs);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EpisodicMemory.class)
    public ConversationViewService conversationViewService(SessionManager sessionManager,
                                                           EpisodicMemory episodicMemory) {
        return new DefaultConversationViewService(sessionManager, episodicMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public MediaDataExtractor mediaDataExtractor(ObjectMapper objectMapper) {
        return new MediaDataExtractor(objectMapper);
    }

    /**
     * AgentPersistenceHandler bean — 聚合所有持久化操作。
     */
    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.persistence.AgentPersistenceHandler agentPersistenceHandler(
            AgentConfigProperties config,
            SessionManager sessionManager,
            @Autowired(required = false) WorkingMemory workingMemory,
            @Autowired(required = false) ConversationHistoryStore conversationHistoryStore,
            @Autowired(required = false) ConversationViewService conversationViewService,
            @Autowired(required = false) RealtimeExtractor realtimeExtractor,
            @Autowired(required = false) InjectionRecordRepository injectionRecordRepository,
            @Autowired(required = false) com.lifepilot.interaction.web.repository.AttachmentRepository attachmentRepository,
            @Autowired(required = false) com.lifepilot.memory.experience.ExperienceSummarizer experienceSummarizer,
            @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker,
            @Autowired(required = false) com.lifepilot.memory.experience.ContrastiveLearner contrastiveLearner,
            @Autowired(required = false) com.lifepilot.memory.experience.SubtaskReflector subtaskReflector) {
        return new com.lifepilot.agent.persistence.AgentPersistenceHandler(
                config, sessionManager, workingMemory, conversationHistoryStore,
                conversationViewService, realtimeExtractor, injectionRecordRepository,
                attachmentRepository, experienceSummarizer, effectivenessTracker,
                contrastiveLearner, subtaskReflector);
    }

    /**
     * ReactAgentLoop bean — ReAct 架构核心循环。
     *
     * <p>通过 {@code @Autowired(required = false)} 注入可选依赖，
     * 在依赖注入阶段自动解析。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactAgentLoop reactAgentLoop(ContextAssembler contextAssembler,
                               LlmRouter llmRouter,
                               @Autowired(required = false) TraceRecorder traceRecorder,
                               ObjectMapper objectMapper,
                               SessionManager sessionManager,
                               AgentToolProvider agentToolProvider,
                               AgentConfigProperties config,
                               PromptRegistry promptRegistry,
                               com.lifepilot.agent.persistence.AgentPersistenceHandler persistenceHandler,
                               @Autowired(required = false) MultimodalRouter multimodalRouter,
                               @Autowired(required = false) MediaDataExtractor mediaDataExtractor,
                               @Autowired(required = false) MediaValidator mediaValidator,
                               @Autowired(required = false) MediaProcessor mediaProcessor,
                               @Autowired(required = false) WorkingMemory workingMemory,
                               @Autowired(required = false) ConversationHistoryStore conversationHistoryStore,
                               @Autowired(required = false) ConversationViewService conversationViewService,
                               @Autowired(required = false) RealtimeExtractor realtimeExtractor,
                               @Autowired(required = false) InjectionRecordRepository injectionRecordRepository,
                               @Autowired(required = false) SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                               @Autowired(required = false) KnowledgeBaseRepository knowledgeBaseRepository,
                               @Autowired(required = false) A2uiProperties a2uiProperties,
                               @Autowired(required = false) com.lifepilot.interaction.web.repository.AttachmentRepository attachmentRepository,
                               @Autowired(required = false) SuspendStore suspendStore,
                               @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
                               @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
                               @Autowired(required = false) com.lifepilot.memory.procedural.IntentMatcher intentMatcher,
                               @Autowired(required = false) com.lifepilot.memory.experience.ExperienceSummarizer experienceSummarizer,
                               @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker,
                               @Autowired(required = false) com.lifepilot.memory.experience.ContrastiveLearner contrastiveLearner,
                               @Autowired(required = false) com.lifepilot.memory.experience.SubtaskReflector subtaskReflector,
                               SharedScheduler sharedScheduler) {
        log.info("Agent 引擎初始化完成（ReAct 架构，追踪{}，记忆系统{}，实时提取{}，多模态{}，A2UI{}，挂起-恢复{}，L4反馈{}，经验总结{}，经验增强{}）",
                traceRecorder != null ? "已启用" : "未启用",
                workingMemory != null ? "已启用" : "未启用",
                realtimeExtractor != null ? "已启用" : "未启用",
                multimodalRouter != null ? "已启用" : "未启用（纯文本模式）",
                a2uiProperties != null && a2uiProperties.enabled() ? "已启用" : "未启用",
                suspendStore != null ? "已启用" : "未启用",
                proceduralMemory != null && intentMatcher != null ? "已启用" : "未启用",
                experienceSummarizer != null ? "已启用" : "未启用",
                effectivenessTracker != null ? "已启用" : "未启用");
        return new ReactAgentLoop(contextAssembler, llmRouter, traceRecorder, objectMapper,
                sessionManager, agentToolProvider, config, promptRegistry,
                persistenceHandler,
                multimodalRouter, mediaDataExtractor, mediaValidator, mediaProcessor,
                workingMemory, conversationHistoryStore,
                conversationViewService, realtimeExtractor, injectionRecordRepository,
                sessionKnowledgeBaseRepository, knowledgeBaseRepository, a2uiProperties,
                attachmentRepository, suspendStore, eventPublisher,
                proceduralMemory, intentMatcher, experienceSummarizer,
                effectivenessTracker, contrastiveLearner, subtaskReflector,
                sharedScheduler);
    }
}
