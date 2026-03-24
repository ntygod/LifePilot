package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.checkpoint.AgentCheckpointStore;
import com.lifepilot.agent.checkpoint.SqliteAgentCheckpointStore;
import com.lifepilot.agent.context.CompactionEngine;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.ContextEngine;
import com.lifepilot.agent.context.PreCompactionMemoryFlushEngine;
import com.lifepilot.agent.context.ProviderMessageBuilder;
import com.lifepilot.agent.context.SessionPruningEngine;
import com.lifepilot.agent.context.TranscriptCompactionBoundaryResolver;
import com.lifepilot.agent.context.TranscriptHygieneEngine;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.llm.config.LlmAutoConfiguration;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.memory.document.MemoryDocumentRepository;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceProperties;
import com.lifepilot.notification.PassiveNotificationQueue;
import com.lifepilot.observability.context.ContextReportRepository;
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
 * Agent \u81ea\u52a8\u914d\u7f6e\u3002
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
    @ConditionalOnMissingBean
    public SessionPruningEngine sessionPruningEngine(
            AgentConfigProperties config,
            ObjectMapper objectMapper) {
        return new SessionPruningEngine(config, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranscriptHygieneEngine transcriptHygieneEngine(AgentConfigProperties config) {
        return new TranscriptHygieneEngine(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderMessageBuilder providerMessageBuilder(
            TranscriptHygieneEngine transcriptHygieneEngine) {
        return new ProviderMessageBuilder(transcriptHygieneEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranscriptCompactionBoundaryResolver transcriptCompactionBoundaryResolver(
            ObjectMapper objectMapper) {
        return new TranscriptCompactionBoundaryResolver(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactionEngine compactionEngine(
            AgentConfigProperties config,
            SessionTranscriptRepository sessionTranscriptRepository,
            SessionStoreRepository sessionStoreRepository,
            TranscriptCompactionBoundaryResolver transcriptCompactionBoundaryResolver,
            PromptRegistry promptRegistry,
            GenerationRouter generationRouter,
            ObjectMapper objectMapper,
            @Autowired(required = false) PreCompactionMemoryFlushEngine preCompactionMemoryFlushEngine) {
        return new CompactionEngine(
                config,
                sessionTranscriptRepository,
                sessionStoreRepository,
                transcriptCompactionBoundaryResolver,
                promptRegistry,
                generationRouter,
                objectMapper,
                preCompactionMemoryFlushEngine
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PreCompactionMemoryFlushEngine preCompactionMemoryFlushEngine(
            MemoryDocumentRepository memoryDocumentRepository,
            SessionTranscriptRepository sessionTranscriptRepository,
            SessionStoreRepository sessionStoreRepository,
            ObjectMapper objectMapper) {
        return new PreCompactionMemoryFlushEngine(
                memoryDocumentRepository,
                sessionTranscriptRepository,
                sessionStoreRepository,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextEngine contextEngine(
            AgentConfigProperties config,
            SessionPruningEngine sessionPruningEngine,
            TranscriptCompactionBoundaryResolver transcriptCompactionBoundaryResolver,
            @Autowired(required = false) SessionTranscriptRepository sessionTranscriptRepository,
            @Autowired(required = false) SessionWorkspaceService workspaceService,
            @Autowired(required = false) WorkspaceProperties workspaceProperties,
            @Autowired(required = false) SessionArtifactRepository sessionArtifactRepository,
            @Autowired(required = false) ContextReportRepository contextReportRepository) {
        return new ContextEngine(
                config,
                sessionPruningEngine,
                transcriptCompactionBoundaryResolver,
                sessionTranscriptRepository,
                workspaceService,
                workspaceProperties,
                sessionArtifactRepository,
                contextReportRepository
        );
    }

    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler contextAssembler(
            AgentConfigProperties config,
            PromptRegistry promptRegistry,
            @Autowired(required = false) DataRedactor dataRedactor,
            @Autowired(required = false) SemanticMemory semanticMemory,
            @Autowired(required = false) PassiveNotificationQueue passiveNotificationQueue,
            @Autowired(required = false) com.lifepilot.memory.config.MemoryProperties memoryProperties,
            @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
            @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker,
            @Autowired(required = false) com.lifepilot.skill.registry.SkillRegistry skillRegistry,
            @Autowired(required = false) GenerationRouter generationRouter,
            @Autowired(required = false) ContextEngine contextEngine) {
        log.info("Agent \u5f15\u64ce: \u6ce8\u518c ContextAssembler\uff0ccontextEngine={}\uff0cL3={}\uff0cL4={}",
                contextEngine != null ? "enabled" : "disabled",
                semanticMemory != null ? "enabled" : "disabled",
                proceduralMemory != null ? "enabled" : "disabled");
        return new ContextAssembler(
                config,
                promptRegistry,
                dataRedactor,
                semanticMemory,
                passiveNotificationQueue,
                memoryProperties,
                proceduralMemory,
                effectivenessTracker,
                skillRegistry,
                generationRouter,
                contextEngine);
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
        log.info("Agent \u68c0\u67e5\u70b9\u6e05\u7406\u4efb\u52a1\u5df2\u6ce8\u518c: interval={}ms, maxAge={}",
                intervalMs, config.getCheckpoint().getMaxAge());
        return store;
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
            @Autowired(required = false) SessionWorkspaceService workspaceService,
            TranscriptStore transcriptStore,
            @Autowired(required = false) RealtimeExtractor realtimeExtractor,
            @Autowired(required = false) InjectionRecordRepository injectionRecordRepository,
            @Autowired(required = false) com.lifepilot.interaction.web.repository.AttachmentRepository attachmentRepository,
            @Autowired(required = false) com.lifepilot.memory.experience.ExperienceSummarizer experienceSummarizer,
            @Autowired(required = false) com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker,
            @Autowired(required = false) com.lifepilot.memory.experience.ContrastiveLearner contrastiveLearner,
            @Autowired(required = false) com.lifepilot.memory.experience.SubtaskReflector subtaskReflector,
            @Autowired(required = false) CompactionEngine compactionEngine) {
        return new com.lifepilot.agent.persistence.AgentPersistenceHandler(
                config,
                workspaceService,
                transcriptStore,
                realtimeExtractor,
                injectionRecordRepository,
                attachmentRepository,
                experienceSummarizer,
                effectivenessTracker,
                contrastiveLearner,
                subtaskReflector,
                compactionEngine);
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
            ProviderMessageBuilder providerMessageBuilder,
            AgentToolProvider agentToolProvider,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            @Autowired(required = false) TraceRecorder traceRecorder,
            @Autowired(required = false) A2uiProperties a2uiProperties,
            @Autowired(required = false) TranscriptStore transcriptStore,
            @Autowired(required = false) MultimodalRouter multimodalRouter,
            @Autowired(required = false) MediaDataExtractor mediaDataExtractor,
            @Autowired(required = false) org.springframework.context.ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory,
            @Autowired(required = false) com.lifepilot.memory.procedural.IntentMatcher intentMatcher,
            SharedScheduler sharedScheduler) {
        return new ReactAgentLoop(
                contextAssembler,
                providerMessageBuilder,
                agentToolProvider,
                config,
                objectMapper,
                traceRecorder,
                a2uiProperties,
                transcriptStore,
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
            GenerationRouter generationRouter,
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
                generationRouter,
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
