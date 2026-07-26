package com.lifepilot.agent.learning.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.interaction.web.service.ConversationSummaryGenerator;
import com.lifepilot.agent.learning.ConversationCompletionHook;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.agent.learning.consolidation.ConsolidationScheduler;
import com.lifepilot.agent.learning.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.agent.learning.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.agent.learning.consolidation.ExperiencePromoter;
import com.lifepilot.agent.learning.consolidation.PreferenceConsolidator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.conversation.event.ConversationCompletedEvent;
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
    private volatile String lastIdleConsolidationFailureSignature;

    public AgentLearningAutoConfiguration(AgentLearningProperties properties,
                                          @Lazy JdbcTemplate jdbcTemplate,
                                          ObjectProvider<ConsolidationScheduler> consolidationSchedulerProvider) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.consolidationSchedulerProvider = consolidationSchedulerProvider;
        this.lastIdleConsolidationTime = Instant.now();
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
    public RelationExtractionStep relationExtractionStep(GenerationRouter generationRouter,
                                                         PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 RelationExtractionStep");
        return new RelationExtractionStep(generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RealtimeExtractor realtimeExtractor(GenerationRouter generationRouter,
                                               SemanticMemory semanticMemory,
                                               ExtractionValidator extractionValidator,
                                               JdbcTemplate jdbcTemplate,
                                               PromptRegistry promptRegistry,
                                               ChatTurnMemorySnapshotRepository snapshotRepository,
                                               Clock clock,
                                               MemoryAccessPolicy memoryAccessPolicy,
                                               MemoryExtractionCandidateRepository candidateRepository,
                                               MemoryInjectionDetector injectionDetector,
                                               RelationExtractionStep relationExtractionStep) {
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
            GenerationRouter generationRouter,
            EmbeddingRouter embeddingRouter,
            AgentLearningProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 EpisodicToProceduralConsolidator");
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
            SemanticMemory semanticMemory,
            EpisodicMemory episodicMemory,
            ProceduralMemory proceduralMemory,
            GenerationRouter generationRouter,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 UserProfileConsolidator");
        Duration llmTimeout = Duration.ofSeconds(positive(
                properties.getConsolidation().getUserProfileLlmTimeoutSeconds(),
                "用户画像巩固 LLM 超时秒数"));
        return new UserProfileConsolidator(semanticMemory, episodicMemory,
                proceduralMemory, generationRouter, promptRegistry, llmTimeout);
    }

    /** 对话完成钩子 — 每轮结束后做摘要生成与画像巩固；主动性由 InitiativeEventListener 独立驱动。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConversationSummaryGenerator.class, UserProfileConsolidator.class})
    public ConversationCompletionHook conversationCompletionHook(
            ConversationSummaryGenerator summaryGenerator,
            UserProfileConsolidator userProfileConsolidator) {
        log.info("记忆模块: 注册 ConversationCompletionHook");
        return new ConversationCompletionHook(summaryGenerator, userProfileConsolidator);
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
            EpisodicToProceduralConsolidator proceduralConsolidator,
            AgentLearningProperties properties,
            PreferenceConsolidator preferenceConsolidator,
            ExperienceMerger experienceMerger,
            UserProfileConsolidator userProfileConsolidator,
            ExperiencePromoter experiencePromoter,
            AssociationCandidateGenerator remGenerator,
            AssociationConsolidator remConsolidator,
            AssociationCandidateApplier remApplier) {
        log.info("记忆模块: 注册 ConsolidationPipeline, preferenceSync={}, experienceLift={}, experienceMerge={}, profileConsolidate={}, remAssociation={}",
                "enabled",
                "enabled",
                "enabled",
                "enabled",
                properties.getRem().isEnabled() ? "enabled" : "disabled");
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
        Duration debounce = Duration.ofMinutes(positive(
                properties.getConsolidation().getProfileDebounceMinutes(),
                "用户画像巩固防抖分钟数"));
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
    public AssociationCandidateGenerator associationCandidateGenerator(
            SemanticMemory semanticMemory,
            HybridRetriever hybridRetriever,
            GenerationRouter generationRouter,
            AgentLearningProperties properties) {
        return new AssociationCandidateGenerator(
                semanticMemory, hybridRetriever, generationRouter, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssociationConsolidator associationConsolidator(
            AgentLearningProperties properties,
            AssociationCandidateStore store) {
        return new AssociationConsolidator(properties, store);
    }

    @Bean
    @ConditionalOnMissingBean
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
            GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 ExperienceMerger");
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
            GenerationRouter generationRouter,
            JdbcTemplate jdbcTemplate,
            AgentLearningProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 ForgettingEngine");
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
            GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties,
            TrajectoryQualityAssessor qualityAssessor,
            ChatSessionRepository chatSessionRepository,
            ProjectContextResolver projectContextResolver) {
        log.info("记忆模块: 注册 ExperienceSummarizer");
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
            GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties) {
        log.info("记忆模块: 注册 ContrastiveLearner");
        return new ContrastiveLearner(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SubtaskReflector subtaskReflector(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            AgentLearningProperties properties,
            ChatSessionRepository chatSessionRepository,
            ProjectContextResolver projectContextResolver) {
        log.info("记忆模块: 注册 SubtaskReflector");
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
            com.lifepilot.observability.trace.TraceRecorder traceRecorder,
            com.lifepilot.agent.learning.trace.AgentTraceWriter agentTraceWriter) {
        log.info("记忆模块: 注册 AgentTraceListenerRegistrar");
        return new com.lifepilot.agent.learning.trace.AgentTraceListenerRegistrar(traceRecorder, agentTraceWriter);
    }


    // ── 巩固调度（唯一真源：ConsolidationScheduler）──

    /** 对话结束事件 → 触发语义/程序巩固 + 登记画像防抖。 */
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        if (!event.isMemoryLearningEnabled()) {
            log.debug("记忆模块: 对话完成巩固已按本轮记忆边界跳过, sessionId={}, turnId={}, reason={}",
                    event.getSessionId(), event.getTurnId(), event.getMemoryLearningSkipReason());
            return;
        }
        ConsolidationScheduler scheduler = consolidationSchedulerProvider.getObject();
        scheduler.onConversationCompleted();
    }

    /** 每日 cron → 偏好同步 + 经验合并 + 经验提升。 */
    @Scheduled(cron = "${lifepilot.agent.learning.consolidation.cron:0 0 3 * * *}")
    public void scheduledDailyConsolidation() {
        ConsolidationScheduler scheduler = consolidationSchedulerProvider.getObject();
        scheduler.runDailyStages();
    }

    /** 每分钟轮询 → 用户画像防抖触发 + 系统空闲后触发 REM 联想。 */
    @Scheduled(fixedDelayString = "PT1M")
    public void pollConsolidationTriggers() {
        ConsolidationScheduler scheduler = consolidationSchedulerProvider.getObject();

        // 1. 用户画像防抖
        try {
            scheduler.checkProfileDebounce();
        } catch (Exception e) {
            log.warn("记忆模块: 用户画像防抖检查失败，跳过本轮: error={}", messageOf(e));
        }

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
        try {
            scheduler.runIdleStages();
            lastIdleConsolidationFailureSignature = null;
        } catch (Exception e) {
            String signature = e.getClass().getName() + ":" + messageOf(e);
            if (!signature.equals(lastIdleConsolidationFailureSignature)) {
                log.warn("记忆模块: 空闲 REM 联想失败，进入冷却: cooldownMinutes={}, error={}",
                        cooldown, messageOf(e));
                log.debug("记忆模块: 空闲 REM 联想失败堆栈", e);
                lastIdleConsolidationFailureSignature = signature;
            } else {
                log.debug("记忆模块: 空闲 REM 联想仍失败，已在冷却内抑制重复堆栈: error={}", messageOf(e));
            }
        } finally {
            lastIdleConsolidationTime = Instant.now();
        }
    }

    private Instant getLastInteractionTime() {
        String lastInteraction = jdbcTemplate.queryForObject(
                "SELECT MAX(COALESCE(last_activity_at, last_message_at, updated_at, created_at)) FROM session_store",
                String.class);
        if (lastInteraction == null || lastInteraction.isBlank()) {
            return Instant.now();
        }
        return Instant.parse(lastInteraction);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0: " + value);
        }
        return value;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
    }
}
