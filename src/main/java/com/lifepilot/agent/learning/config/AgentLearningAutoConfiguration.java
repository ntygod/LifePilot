package com.lifepilot.agent.learning.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.agent.learning.consolidation.ConsolidationScheduler;
import com.lifepilot.agent.learning.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.agent.learning.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.agent.learning.consolidation.ExperiencePromoter;
import com.lifepilot.agent.learning.consolidation.PreferenceConsolidator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.memory.governance.security.MemoryInjectionDetector;
import com.lifepilot.agent.learning.conflict.ConflictResolutionRepository;
import com.lifepilot.agent.learning.conflict.ConflictResolutionService;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateGenerator;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateStore;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateApplier;
import com.lifepilot.agent.learning.consolidation.association.AssociationConsolidator;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.agent.learning.consolidation.EntityDeduplicator;
import com.lifepilot.agent.learning.consolidation.ExperienceMerger;
import com.lifepilot.agent.learning.experience.ContrastiveLearner;
import com.lifepilot.agent.learning.experience.EffectivenessTracker;
import com.lifepilot.agent.learning.experience.ExperienceSummarizer;
import com.lifepilot.agent.learning.experience.SubtaskReflector;
import com.lifepilot.agent.learning.experience.TrajectoryQualityAssessor;
import com.lifepilot.agent.learning.feedback.FeedbackProcessor;
import com.lifepilot.agent.learning.forgetting.ForgettingEngine;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.agent.learning.staleness.NeighborRefreshService;
import com.lifepilot.agent.learning.staleness.StaleConflictDetector;
import com.lifepilot.agent.learning.staleness.StalenessCoordinator;
import com.lifepilot.agent.learning.staleness.StalenessMarker;
import com.lifepilot.agent.learning.staleness.VectorBasedStaleConflictDetector;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.agent.learning.extraction.ExtractionValidator;
import com.lifepilot.agent.learning.extraction.MemoryExtractionCandidateRepository;
import com.lifepilot.agent.learning.extraction.RealtimeExtractor;
import com.lifepilot.agent.learning.extraction.RelationExtractionStep;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
import com.lifepilot.memory.store.config.MemoryStoreProperties;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Agent 学习层自动装配 — 注册提取、巩固、遗忘、经验总结、反馈等学习组件。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration(after = MemoryStoreAutoConfiguration.class)
@EnableConfigurationProperties(AgentLearningProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentLearningAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentLearningAutoConfiguration.class);

    private final AgentLearningProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ConsolidationScheduler> consolidationSchedulerProvider;

    private volatile Instant lastIdleConsolidationTime;

    public AgentLearningAutoConfiguration(AgentLearningProperties properties,
                                          @Lazy JdbcTemplate jdbcTemplate,
                                          ObjectProvider<ConsolidationScheduler> consolidationSchedulerProvider) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.consolidationSchedulerProvider = consolidationSchedulerProvider;
    }

    // ── 提取 ──

    @Bean
    @ConditionalOnMissingBean
    public ExtractionValidator extractionValidator(AgentLearningProperties properties) {
        log.info("记忆模块: 注册 ExtractionValidator");
        return new ExtractionValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryExtractionCandidateRepository memoryExtractionCandidateRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 MemoryExtractionCandidateRepository");
        return new MemoryExtractionCandidateRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GenerationRouter.class)
    public RelationExtractionStep relationExtractionStep(GenerationRouter generationRouter,
                                                         PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 RelationExtractionStep");
        return new RelationExtractionStep(generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GenerationRouter.class)
    public RealtimeExtractor realtimeExtractor(GenerationRouter generationRouter,
                                               SemanticMemory semanticMemory,
                                               ExtractionValidator extractionValidator,
                                               JdbcTemplate jdbcTemplate,
                                               PromptRegistry promptRegistry,
                                               ChatTurnMemorySnapshotRepository snapshotRepository,
                                               Clock clock,
                                               MemoryAccessPolicy memoryAccessPolicy,
                                               @Nullable MemoryExtractionCandidateRepository candidateRepository,
                                               @Nullable MemoryInjectionDetector injectionDetector,
                                               @Nullable RelationExtractionStep relationExtractionStep) {
        log.info("记忆模块: 注册 RealtimeExtractor");
        return new RealtimeExtractor(generationRouter, semanticMemory, properties, extractionValidator,
                jdbcTemplate, promptRegistry, snapshotRepository, clock, memoryAccessPolicy,
                candidateRepository, injectionDetector, relationExtractionStep);
    }

    // ── 冲突裁决 ──

    @Bean
    @ConditionalOnMissingBean
    public ConflictResolutionRepository conflictResolutionRepository(JdbcTemplate jdbcTemplate,
                                                                     ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 ConflictResolutionRepository");
        return new ConflictResolutionRepository(jdbcTemplate, objectMapper);
    }

    /**
     * 语义冲突裁决服务。注入到 {@link SemanticMemory} 后，upsert 末尾会异步触发
     * LLM 裁决（REPLACE / COEXIST / TIMELINE）。
     *
     * <p>采用 setter 注入挂到 semanticMemory 上，避免构造器循环依赖
     * （ConflictResolutionService 的 applyVerdict 路径需要回调 semanticMemory）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(GenerationRouter.class)
    public ConflictResolutionService conflictResolutionService(
            GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            VectorSearcher vectorSearcher,
            ConflictResolutionRepository conflictResolutionRepository,
            SemanticMemory semanticMemory) {
        log.info("记忆模块: 注册 ConflictResolutionService");
        var service = new ConflictResolutionService(
                generationRouter, promptRegistry, vectorSearcher,
                conflictResolutionRepository, semanticMemory);
        semanticMemory.setConflictResolutionService(service);
        return service;
    }

    // ── Staleness ──

    @Bean
    @ConditionalOnMissingBean
    public StaleConflictDetector staleConflictDetector(
            VectorSearcher vectorSearcher,
            SemanticMemory semanticMemory,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 StaleConflictDetector (staleness.enabled={})",
                properties.getStaleness().isEnabled());
        return new VectorBasedStaleConflictDetector(
                vectorSearcher, semanticMemory, properties.getStaleness());
    }

    @Bean
    @ConditionalOnMissingBean
    public StalenessMarker stalenessMarker(
            SemanticMemory semanticMemory) {
        return new StalenessMarker(semanticMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public NeighborRefreshService neighborRefreshService(
            AgentLearningProperties properties) {
        return new NeighborRefreshService(
                properties.getStaleness());
    }

    /**
     * StalenessCoordinator 通过 setter 注入到 SemanticMemory。
     * 装配触发点：SemanticMemory.upsertWithConflictDetection → afterCommit → coordinator.process。
     */
    @Bean
    @ConditionalOnMissingBean
    public StalenessCoordinator stalenessCoordinator(
            StaleConflictDetector detector,
            StalenessMarker marker,
            NeighborRefreshService refreshService,
            AgentLearningProperties properties,
            SemanticMemory semanticMemory) {
        var coordinator = new StalenessCoordinator(
                detector, marker, refreshService, properties.getStaleness());
        semanticMemory.setStalenessCoordinator(coordinator);
        log.info("记忆模块: 注册 StalenessCoordinator 并注入到 SemanticMemory");
        return coordinator;
    }

    // ── 巩固 ──

    @Bean
    @ConditionalOnMissingBean
    public EpisodicToSemanticConsolidator episodicToSemanticConsolidator(
            EpisodicMemory episodicMemory,
            SemanticMemory semanticMemory,
            JdbcTemplate jdbcTemplate,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 EpisodicToSemanticConsolidator");
        return new EpisodicToSemanticConsolidator(episodicMemory, semanticMemory, jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EpisodicToProceduralConsolidator episodicToProceduralConsolidator(
            JdbcTemplate jdbcTemplate,
            ProceduralMemory proceduralMemory,
            @Nullable GenerationRouter generationRouter,
            @Nullable EmbeddingRouter embeddingRouter,
            AgentLearningProperties properties,
            PromptRegistry promptRegistry) {
        if (generationRouter == null || embeddingRouter == null) {
            log.warn("记忆模块: GenerationRouter 或 EmbeddingRouter 不可用，EpisodicToProceduralConsolidator 将无法执行巩固");
        }
        log.info("记忆模块: 注册 EpisodicToProceduralConsolidator, generationRouterAvailable={}, embeddingRouterAvailable={}",
                generationRouter != null ? "yes" : "no", embeddingRouter != null ? "yes" : "no");
        return new EpisodicToProceduralConsolidator(jdbcTemplate, proceduralMemory,
                generationRouter, embeddingRouter, properties, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public PreferenceConsolidator preferenceConsolidator(
            SemanticMemory semanticMemory,
            ProceduralMemory proceduralMemory) {
        log.info("记忆模块: 注册 PreferenceConsolidator");
        return new PreferenceConsolidator(semanticMemory, proceduralMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public UserProfileConsolidator userProfileConsolidator(
            @Nullable SemanticMemory semanticMemory,
            @Nullable EpisodicMemory episodicMemory,
            @Nullable ProceduralMemory proceduralMemory,
            @Nullable GenerationRouter generationRouter,
            @Nullable PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 UserProfileConsolidator, semanticMemory={}, generationRouter={}",
                semanticMemory != null ? "available" : "missing",
                generationRouter != null ? "available" : "missing");
        Duration llmTimeout = Duration.ofSeconds(
                Math.max(1, properties.getConsolidation().getUserProfileLlmTimeoutSeconds()));
        return new UserProfileConsolidator(semanticMemory, episodicMemory,
                proceduralMemory, generationRouter, promptRegistry, llmTimeout);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExperiencePromoter experiencePromoter(
            SemanticMemory semanticMemory,
            ProceduralMemory proceduralMemory,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 ExperiencePromoter");
        return new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsolidationPipeline consolidationPipeline(
            EpisodicToSemanticConsolidator semanticConsolidator,
            @Nullable EpisodicToProceduralConsolidator proceduralConsolidator,
            AgentLearningProperties properties,
            @Nullable PreferenceConsolidator preferenceConsolidator,
            @Nullable ExperienceMerger experienceMerger,
            @Nullable UserProfileConsolidator userProfileConsolidator,
            @Nullable ExperiencePromoter experiencePromoter,
            @Nullable AssociationCandidateGenerator remGenerator,
            @Nullable AssociationConsolidator remConsolidator,
            @Nullable AssociationCandidateApplier remApplier) {
        log.info("记忆模块: 注册 ConsolidationPipeline, preferenceSync={}, experienceLift={}, experienceMerge={}, profileConsolidate={}, remAssociation={}",
                preferenceConsolidator != null ? "enabled" : "disabled",
                experiencePromoter != null ? "enabled" : "disabled",
                experienceMerger != null ? "enabled" : "disabled",
                userProfileConsolidator != null ? "enabled" : "disabled",
                (remGenerator != null && remConsolidator != null) ? "enabled" : "disabled");
        return new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator,
                properties, preferenceConsolidator, experienceMerger, userProfileConsolidator,
                experiencePromoter, remGenerator, remConsolidator, remApplier);
    }

    /**
     * 巩固调度器 — 巩固系统的唯一调度真源（事件 / cron / 空闲分流）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ConsolidationScheduler consolidationScheduler(ConsolidationPipeline pipeline) {
        Duration debounce = Duration.ofMinutes(
                Math.max(1, properties.getConsolidation().getProfileDebounceMinutes()));
        log.info("记忆模块: 注册 ConsolidationScheduler, profileDebounce={}min", debounce.toMinutes());
        return new ConsolidationScheduler(pipeline, debounce);
    }

    // ── REM 联想 ──

    @Bean
    @ConditionalOnMissingBean
    public AssociationCandidateStore associationCandidateStore() {
        return AssociationCandidateStore.withDefaultCacheDir();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "lifepilot.agent.learning.rem", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AssociationCandidateGenerator associationCandidateGenerator(
            SemanticMemory semanticMemory,
            @Nullable HybridRetriever hybridRetriever,
            @Nullable GenerationRouter generationRouter,
            AgentLearningProperties properties) {
        return new AssociationCandidateGenerator(
                semanticMemory, hybridRetriever, generationRouter, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "lifepilot.agent.learning.rem", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AssociationConsolidator associationConsolidator(
            AgentLearningProperties properties,
            AssociationCandidateStore store) {
        return new AssociationConsolidator(properties, store);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "lifepilot.agent.learning.rem", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AssociationCandidateApplier associationCandidateApplier(
            AgentLearningProperties properties,
            AssociationCandidateStore store,
            SemanticMemory semanticMemory) {
        log.info("记忆模块: 注册 AssociationCandidateApplier, applyMinConfidence={}",
                properties.getRem().getApplyMinConfidence());
        return new AssociationCandidateApplier(properties, store, semanticMemory);
    }

    // ── 经验学习 ──

    @Bean
    @ConditionalOnMissingBean
    public ExperienceMerger experienceMerger(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 ExperienceMerger, generationRouterAvailable={}",
                generationRouter != null ? "yes" : "no");
        return new ExperienceMerger(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityDeduplicator entityDeduplicator(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            AgentLearningProperties properties,
            PlatformTransactionManager transactionManager) {
        log.info("记忆模块: 注册 EntityDeduplicator");
        return new EntityDeduplicator(semanticMemory, vectorSearcher, jdbcTemplate, properties, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public ForgettingEngine forgettingEngine(
            SemanticMemory semanticMemory,
            @Nullable GenerationRouter generationRouter,
            JdbcTemplate jdbcTemplate,
            AgentLearningProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 ForgettingEngine, generationRouterAvailable={}",
                generationRouter != null ? "yes" : "no");
        return new ForgettingEngine(semanticMemory, generationRouter, jdbcTemplate, properties, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public InjectionRecordRepository injectionRecordRepository(JdbcTemplate jdbcTemplate,
                                                               ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 InjectionRecordRepository");
        return new InjectionRecordRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeedbackProcessor feedbackProcessor(
            InjectionRecordRepository injectionRecordRepository,
            SemanticMemory semanticMemory,
            MessageFeedbackRepository feedbackRepository,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 FeedbackProcessor");
        return new FeedbackProcessor(injectionRecordRepository, semanticMemory,
                feedbackRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TrajectoryQualityAssessor trajectoryQualityAssessor(AgentLearningProperties properties) {
        log.info("记忆模块: 注册 TrajectoryQualityAssessor");
        return new TrajectoryQualityAssessor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExperienceSummarizer experienceSummarizer(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties,
            TrajectoryQualityAssessor qualityAssessor,
            @Nullable ChatSessionRepository chatSessionRepository,
            @Nullable ProjectContextResolver projectContextResolver) {
        log.info("记忆模块: 注册 ExperienceSummarizer, generationRouterAvailable={}, projectAware={}",
                generationRouter != null ? "yes" : "no",
                projectContextResolver != null && chatSessionRepository != null);
        return new ExperienceSummarizer(semanticMemory, vectorSearcher, generationRouter,
                promptRegistry, properties, qualityAssessor,
                chatSessionRepository, projectContextResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public EffectivenessTracker effectivenessTracker(
            SemanticMemory semanticMemory,
            InjectionRecordRepository injectionRecordRepository,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 EffectivenessTracker");
        return new EffectivenessTracker(
                semanticMemory, injectionRecordRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContrastiveLearner contrastiveLearner(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 ContrastiveLearner, generationRouterAvailable={}",
                generationRouter != null ? "yes" : "no");
        return new ContrastiveLearner(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubtaskReflector subtaskReflector(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties,
            @Nullable ChatSessionRepository chatSessionRepository,
            @Nullable ProjectContextResolver projectContextResolver) {
        log.info("记忆模块: 注册 SubtaskReflector, generationRouterAvailable={}, projectAware={}",
                generationRouter != null ? "yes" : "no",
                projectContextResolver != null && chatSessionRepository != null);
        return new SubtaskReflector(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties,
                chatSessionRepository, projectContextResolver);
    }

    // ── IntentMatcher ──

    @Bean
    @ConditionalOnMissingBean
    public IntentMatcher intentMatcher(
            ProceduralMemory proceduralMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            MemoryStoreProperties storeProperties) {
        log.info("记忆模块: 注册 IntentMatcher");
        return new IntentMatcher(proceduralMemory, vectorSearcher, jdbcTemplate, storeProperties);
    }

    // ── 学习轨迹持久化（打通程序巩固数据源）──

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.learning.trace.AgentTraceWriter agentTraceWriter(
            JdbcTemplate jdbcTemplate,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 AgentTraceWriter");
        return new com.lifepilot.agent.learning.trace.AgentTraceWriter(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.learning.trace.AgentTraceListenerRegistrar agentTraceListenerRegistrar(
            @Nullable com.lifepilot.observability.trace.TraceRecorder traceRecorder,
            com.lifepilot.agent.learning.trace.AgentTraceWriter agentTraceWriter) {
        log.info("记忆模块: 注册 AgentTraceListenerRegistrar, traceRecorder={}",
                traceRecorder != null ? "available" : "unavailable");
        return new com.lifepilot.agent.learning.trace.AgentTraceListenerRegistrar(traceRecorder, agentTraceWriter);
    }


    // ── 巩固调度（唯一真源：ConsolidationScheduler）──

    /** 对话结束事件 → 触发语义/程序巩固 + 登记画像防抖。 */
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        ConsolidationScheduler scheduler = consolidationSchedulerProvider.getIfAvailable();
        if (scheduler != null) {
            scheduler.onConversationCompleted();
        }
    }

    /** 每日 cron → 偏好同步 + 经验合并 + 经验提升。 */
    @Scheduled(cron = "${lifepilot.agent.learning.consolidation.cron:0 0 3 * * *}")
    public void scheduledDailyConsolidation() {
        ConsolidationScheduler scheduler = consolidationSchedulerProvider.getIfAvailable();
        if (scheduler != null) {
            scheduler.runDailyStages();
        }
    }

    /** 每分钟轮询 → 用户画像防抖触发 + 系统空闲后触发 REM 联想。 */
    @Scheduled(fixedDelayString = "PT1M")
    public void pollConsolidationTriggers() {
        ConsolidationScheduler scheduler = consolidationSchedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return;
        }

        // 1. 用户画像防抖
        scheduler.checkProfileDebounce();

        // 2. 空闲 REM 联想
        Instant lastInteraction = getLastInteractionTime();
        int idleThreshold = properties.getConsolidation().getIdleThresholdMinutes();
        if (Duration.between(lastInteraction, Instant.now()).toMinutes() < idleThreshold) {
            return;
        }

        int cooldown = properties.getConsolidation().getIdleCooldownMinutes();
        if (lastIdleConsolidationTime != null
                && Duration.between(lastIdleConsolidationTime, Instant.now()).toMinutes() < cooldown) {
            return;
        }

        log.info("记忆模块: 空闲触发 REM 联想, idleThresholdMinutes={}", idleThreshold);
        scheduler.runIdleStages();
        lastIdleConsolidationTime = Instant.now();
    }

    private Instant getLastInteractionTime() {
        try {
            String lastInteraction = jdbcTemplate.queryForObject(
                    "SELECT MAX(COALESCE(last_activity_at, last_message_at, updated_at, created_at)) FROM session_store",
                    String.class);
            if (lastInteraction == null || lastInteraction.isBlank()) {
                return Instant.now();
            }
            return Instant.parse(lastInteraction);
        } catch (Exception e) {
            log.debug("记忆模块: 查询最近交互时间失败，回退到当前时间: {}", e.getMessage());
            return Instant.now();
        }
    }
}
