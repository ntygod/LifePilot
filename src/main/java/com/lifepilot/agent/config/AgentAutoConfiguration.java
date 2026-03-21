package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.checkpoint.AgentCheckpointStore;
import com.lifepilot.agent.checkpoint.SqliteAgentCheckpointStore;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.conversation.DefaultConversationViewService;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.config.LlmAutoConfiguration;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceProperties;
import com.lifepilot.notification.PassiveNotificationQueue;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.tool.config.ToolAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Agent 自动配置。
 *
 * @author zsg
 * @since 2026-03-20
 */
@AutoConfiguration(after = {ToolAutoConfiguration.class, LlmAutoConfiguration.class})
@EnableConfigurationProperties(AgentConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.agent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler contextAssembler(
            AgentConfigProperties config,
            PromptRegistry promptRegistry,
            @Autowired(required = false) ConversationViewService conversationViewService,
            @Autowired(required = false) SessionWorkspaceService workspaceService,
            @Autowired(required = false) WorkspaceProperties workspaceProperties,
            @Autowired(required = false) DataRedactor dataRedactor,
            @Autowired(required = false) SemanticMemory semanticMemory,
            @Autowired(required = false) PassiveNotificationQueue passiveNotificationQueue,
            @Autowired(required = false) com.lifepilot.memory.config.MemoryProperties memoryProperties,
            @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
            @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker,
            @Autowired(required = false) com.lifepilot.skill.registry.SkillRegistry skillRegistry,
            @Autowired(required = false) LlmRouter llmRouter) {
        log.info("Agent 引擎: 注册 ContextAssembler（history={}，workspace={}，L3={}，L4={}）",
                conversationViewService != null ? "enabled" : "disabled",
                workspaceService != null ? "enabled" : "disabled",
                semanticMemory != null ? "enabled" : "disabled",
                proceduralMemory != null ? "enabled" : "disabled");
        return new ContextAssembler(
                config,
                promptRegistry,
                conversationViewService,
                workspaceService,
                workspaceProperties,
                dataRedactor,
                semanticMemory,
                passiveNotificationQueue,
                memoryProperties,
                proceduralMemory,
                effectivenessTracker,
                skillRegistry,
                llmRouter);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper,
                                         AgentConfigProperties config,
                                         SharedScheduler sharedScheduler) {
        var manager = new SessionManager(jdbcTemplate, objectMapper, config);
        long intervalMs = config.getSession().getCleanupIntervalMs();
        sharedScheduler.cleanup().scheduleAtFixedRate(
                manager::cleanupExpiredSessions,
                intervalMs, intervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("会话过期清理任务已注册: interval={}ms", intervalMs);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean(AgentCheckpointStore.class)
    @ConditionalOnProperty(prefix = "lifepilot.agent.checkpoint", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentCheckpointStore agentCheckpointStore(JdbcTemplate jdbcTemplate,
                                                     AgentConfigProperties config,
                                                     SharedScheduler sharedScheduler) {
        var store = new SqliteAgentCheckpointStore(jdbcTemplate);
        long intervalMs = config.getCheckpoint().getCleanupIntervalMs();
        sharedScheduler.cleanup().scheduleAtFixedRate(
                () -> store.cleanExpired(config.getCheckpoint().getMaxAge()),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Agent 检查点清理任务已注册: interval={}ms, maxAge={}",
                intervalMs, config.getCheckpoint().getMaxAge());
        return store;
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationViewService conversationViewService(SessionManager sessionManager,
                                                           ChatMessageRepository chatMessageRepository) {
        return new DefaultConversationViewService(sessionManager, chatMessageRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public MediaDataExtractor mediaDataExtractor(ObjectMapper objectMapper) {
        return new MediaDataExtractor(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.persistence.AgentPersistenceHandler agentPersistenceHandler(
            AgentConfigProperties config,
            SessionManager sessionManager,
            @Autowired(required = false) SessionWorkspaceService workspaceService,
            @Autowired(required = false) ConversationHistoryStore conversationHistoryStore,
            @Autowired(required = false) RealtimeExtractor realtimeExtractor,
            @Autowired(required = false) InjectionRecordRepository injectionRecordRepository,
            @Autowired(required = false) com.lifepilot.interaction.web.repository.AttachmentRepository attachmentRepository,
            @Autowired(required = false) com.lifepilot.memory.experience.ExperienceSummarizer experienceSummarizer,
            @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker,
            @Autowired(required = false) com.lifepilot.memory.experience.ContrastiveLearner contrastiveLearner,
            @Autowired(required = false) com.lifepilot.memory.experience.SubtaskReflector subtaskReflector) {
        return new com.lifepilot.agent.persistence.AgentPersistenceHandler(
                config,
                sessionManager,
                workspaceService,
                conversationHistoryStore,
                realtimeExtractor,
                injectionRecordRepository,
                attachmentRepository,
                experienceSummarizer,
                effectivenessTracker,
                contrastiveLearner,
                subtaskReflector);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.streaming.StreamingEventHandler streamingEventHandler(
            ObjectMapper objectMapper,
            @Autowired(required = false) A2uiProperties a2uiProperties,
            @Autowired(required = false) SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Autowired(required = false) KnowledgeBaseRepository knowledgeBaseRepository) {
        return new com.lifepilot.agent.streaming.StreamingEventHandler(
                objectMapper, a2uiProperties, sessionKnowledgeBaseRepository, knowledgeBaseRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactAgentLoop reactAgentLoop(
            ContextAssembler contextAssembler,
            AgentToolProvider agentToolProvider,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            @Autowired(required = false) TraceRecorder traceRecorder,
            @Autowired(required = false) A2uiProperties a2uiProperties,
            @Autowired(required = false) MultimodalRouter multimodalRouter,
            @Autowired(required = false) MediaDataExtractor mediaDataExtractor,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
            @Autowired(required = false) com.lifepilot.memory.procedural.IntentMatcher intentMatcher,
            SharedScheduler sharedScheduler) {
        return new ReactAgentLoop(
                contextAssembler,
                agentToolProvider,
                config,
                objectMapper,
                traceRecorder,
                a2uiProperties,
                multimodalRouter,
                mediaDataExtractor,
                eventPublisher,
                proceduralMemory,
                intentMatcher,
                sharedScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.orchestration.AgentOrchestrator agentOrchestrator(
            ReactAgentLoop reactAgentLoop,
            com.lifepilot.agent.persistence.AgentPersistenceHandler persistenceHandler,
            com.lifepilot.agent.streaming.StreamingEventHandler streamingEventHandler,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            SessionManager sessionManager,
            LlmRouter llmRouter,
            @Autowired(required = false) TraceRecorder traceRecorder,
            @Autowired(required = false) MultimodalRouter multimodalRouter,
            @Autowired(required = false) MediaValidator mediaValidator,
            @Autowired(required = false) MediaProcessor mediaProcessor,
            @Autowired(required = false) AgentCheckpointStore checkpointStore,
            @Autowired(required = false) SuspendStore suspendStore,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
            SharedScheduler sharedScheduler) {
        return new com.lifepilot.agent.orchestration.AgentOrchestrator(
                reactAgentLoop,
                persistenceHandler,
                streamingEventHandler,
                config,
                objectMapper,
                sessionManager,
                llmRouter,
                traceRecorder,
                multimodalRouter,
                mediaValidator,
                mediaProcessor,
                checkpointStore,
                suspendStore,
                eventPublisher,
                sharedScheduler);
    }
}
