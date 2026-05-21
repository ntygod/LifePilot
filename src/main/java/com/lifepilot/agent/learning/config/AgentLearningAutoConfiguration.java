package com.lifepilot.agent.learning.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.agent.learning.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.agent.learning.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.agent.learning.consolidation.PreferenceConsolidator;
import com.lifepilot.agent.learning.consolidation.UserProfileConsolidator;
import com.lifepilot.memory.governance.security.MemoryInjectionDetector;
import com.lifepilot.agent.learning.conflict.ConflictResolutionRepository;
import com.lifepilot.agent.learning.conflict.ConflictResolutionService;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateGenerator;
import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateStore;
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
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
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
@EnableConfigurationProperties({AgentLearningProperties.class, MemoryProperties.class})
@EnableScheduling
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentLearningAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentLearningAutoConfiguration.class);

    private final MemoryProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ConsolidationPipeline> consolidationPipelineProvider;

    private volatile Instant lastIdleConsolidationTime;

    public AgentLearningAutoConfiguration(MemoryProperties properties,
                                          @Lazy JdbcTemplate jdbcTemplate,
                                          ObjectProvider<ConsolidationPipeline> consolidationPipelineProvider) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.consolidationPipelineProvider = consolidationPipelineProvider;
    }

    // ── 提取 ──

    @Bean
    @ConditionalOnMissingBean
    public ExtractionValidator extractionValidator(MemoryProperties properties) {
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
    public RealtimeExtractor realtimeExtractor(@Nullable GenerationRouter generationRouter,
                                               SemanticMemory semanticMemory,
                                               ExtractionValidator extractionValidator,
                                               JdbcTemplate jdbcTemplate,
                                               PromptRegistry promptRegistry,
                                               @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                                               Clock clock,
                                               MemoryAccessPolicy memoryAccessPolicy,
                                               @Nullable MemoryExtractionCandidateRepository candidateRepository,
                                               @Nullable MemoryInjectionDetector injectionDetector) {
        if (generationRouter == null) {
            log.warn("记忆模块: GenerationRouter 不可用，RealtimeExtractor 将无法执行提取");
        }
        log.info("记忆模块: 注册 RealtimeExtractor, generationRouterAvailable={}", generationRouter != null ? "yes" : "no");
        return new RealtimeExtractor(generationRouter, semanticMemory, properties, extractionValidator,
                jdbcTemplate, promptRegistry, snapshotRepository, clock, memoryAccessPolicy, candidateRepository, injectionDetector);
    }

    // ── 冲突裁决 ──

    @Bean
    @ConditionalOnMissingBean
    public ConflictResolutionRepository conflictResolutionRepository(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 ConflictResolutionRepository");
        return new ConflictResolutionRepository(jdbcTemplate);
    }

    /**
     * Task 24：语义冲突裁决服务。注入到 {@link SemanticMemory} 后，
     * upsert 末尾会异步触发 LLM 裁决（REPLACE / COEXIST / TIMELINE）。
     *
     * <p>采用 setter 注入挂到 semanticMemory 上，避免构造器循环依赖
     * （ConflictResolutionService 的 applyVerdict 路径需要回调 semanticMemory）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public ConflictResolutionService conflictResolutionService(
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            VectorSearcher vectorSearcher,
            ConflictResolutionRepository conflictResolutionRepository,
            SemanticMemory semanticMemory) {
        log.info("记忆模块: 注册 ConflictResolutionService, generationRouterAvailable={}",
                generationRouter != null ? "yes" : "no");
        var service = new ConflictResolutionService(
                generationRouter, promptRegistry, vectorSearcher,
                conflictResolutionRepository, semanticMemory);
        semanticMemory.setConflictResolutionService(service);
        return service;
    }

    // ── Staleness ──

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({VectorSearcher.class, SemanticMemory.class})
    public StaleConflictDetector staleConflictDetector(
            VectorSearcher vectorSearcher,
            SemanticMemory semanticMemory,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 StaleConflictDetector (staleness.enabled={})",
                properties.getStaleness().isEnabled());
        return new VectorBasedStaleConflictDetector(
                vectorSearcher, semanticMemory, properties.getStaleness());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public StalenessMarker stalenessMarker(
            SemanticMemory semanticMemory) {
        return new StalenessMarker(semanticMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public NeighborRefreshService neighborRefreshService(
            MemoryProperties properties) {
        return new NeighborRefreshService(
                properties.getStaleness());
    }

    /**
     * StalenessCoordinator 通过 setter 注入到 SemanticMemory。
     * 装配触发点：SemanticMemory.upsertWithConflictDetection → afterCommit → coordinator.process。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({StaleConflictDetector.class,
                         StalenessMarker.class,
                         SemanticMemory.class})
    public StalenessCoordinator stalenessCoordinator(
            StaleConflictDetector detector,
            StalenessMarker marker,
            NeighborRefreshService refreshService,
            MemoryProperties properties,
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
    @ConditionalOnBean({EpisodicMemory.class, SemanticMemory.class})
    public EpisodicToSemanticConsolidator episodicToSemanticConsolidator(
            EpisodicMemory episodicMemory,
            SemanticMemory semanticMemory,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 EpisodicToSemanticConsolidator");
        return new EpisodicToSemanticConsolidator(episodicMemory, semanticMemory, jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProceduralMemory.class)
    public EpisodicToProceduralConsolidator episodicToProceduralConsolidator(
            JdbcTemplate jdbcTemplate,
            ProceduralMemory proceduralMemory,
            @Nullable GenerationRouter generationRouter,
            @Nullable EmbeddingRouter embeddingRouter,
            MemoryProperties properties,
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
    @ConditionalOnBean({SemanticMemory.class, ProceduralMemory.class})
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
    @ConditionalOnBean(EpisodicToSemanticConsolidator.class)
    public ConsolidationPipeline consolidationPipeline(
            EpisodicToSemanticConsolidator semanticConsolidator,
            @Nullable EpisodicToProceduralConsolidator proceduralConsolidator,
            MemoryProperties properties,
            @Nullable PreferenceConsolidator preferenceConsolidator,
            @Nullable SemanticMemory semanticMemory,
            @Nullable ProceduralMemory proceduralMemory,
            @Nullable ExperienceMerger experienceMerger,
            @Nullable UserProfileConsolidator userProfileConsolidator,
            @Nullable AssociationCandidateGenerator remGenerator,
            @Nullable AssociationConsolidator remConsolidator) {
        log.info("记忆模块: 注册 ConsolidationPipeline, preferenceSync={}, experienceLift={}, experienceMerge={}, profileConsolidate={}, remAssociation={}",
                preferenceConsolidator != null ? "enabled" : "disabled",
                semanticMemory != null && proceduralMemory != null ? "enabled" : "disabled",
                experienceMerger != null ? "enabled" : "disabled",
                userProfileConsolidator != null ? "enabled" : "disabled",
                (remGenerator != null && remConsolidator != null) ? "enabled" : "disabled");
        return new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator,
                properties, preferenceConsolidator, semanticMemory, proceduralMemory,
                experienceMerger, userProfileConsolidator, remGenerator, remConsolidator);
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
            name = "lifepilot.memory.rem.enabled", havingValue = "true")
    public AssociationCandidateGenerator associationCandidateGenerator(
            SemanticMemory semanticMemory,
            @Nullable HybridRetriever hybridRetriever,
            @Nullable GenerationRouter generationRouter,
            MemoryProperties properties) {
        return new AssociationCandidateGenerator(
                semanticMemory, hybridRetriever, generationRouter, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.rem.enabled", havingValue = "true")
    public AssociationConsolidator associationConsolidator(
            MemoryProperties properties,
            AssociationCandidateStore store) {
        return new AssociationConsolidator(properties, store);
    }

    // ── 经验学习 ──

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public ExperienceMerger experienceMerger(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 ExperienceMerger, generationRouterAvailable={}",
                generationRouter != null ? "yes" : "no");
        return new ExperienceMerger(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public EntityDeduplicator entityDeduplicator(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties,
            PlatformTransactionManager transactionManager) {
        log.info("记忆模块: 注册 EntityDeduplicator");
        return new EntityDeduplicator(semanticMemory, vectorSearcher, jdbcTemplate, properties, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public ForgettingEngine forgettingEngine(
            SemanticMemory semanticMemory,
            @Nullable GenerationRouter generationRouter,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties,
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
    @ConditionalOnBean(SemanticMemory.class)
    public FeedbackProcessor feedbackProcessor(
            InjectionRecordRepository injectionRecordRepository,
            SemanticMemory semanticMemory,
            MessageFeedbackRepository feedbackRepository,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 FeedbackProcessor");
        return new FeedbackProcessor(injectionRecordRepository, semanticMemory,
                feedbackRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TrajectoryQualityAssessor trajectoryQualityAssessor(MemoryProperties properties) {
        log.info("记忆模块: 注册 TrajectoryQualityAssessor");
        return new TrajectoryQualityAssessor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public ExperienceSummarizer experienceSummarizer(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties,
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
    @ConditionalOnBean(SemanticMemory.class)
    public EffectivenessTracker effectivenessTracker(
            SemanticMemory semanticMemory,
            InjectionRecordRepository injectionRecordRepository,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 EffectivenessTracker");
        return new EffectivenessTracker(
                semanticMemory, injectionRecordRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public ContrastiveLearner contrastiveLearner(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 ContrastiveLearner, generationRouterAvailable={}",
                generationRouter != null ? "yes" : "no");
        return new ContrastiveLearner(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public SubtaskReflector subtaskReflector(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            @Nullable GenerationRouter generationRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties,
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
    @ConditionalOnBean({ProceduralMemory.class, VectorSearcher.class})
    public IntentMatcher intentMatcher(
            ProceduralMemory proceduralMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 IntentMatcher");
        return new IntentMatcher(proceduralMemory, vectorSearcher, jdbcTemplate, properties);
    }

    // ── 空闲巩固调度 ──

    @Scheduled(fixedDelayString = "PT1M")
    public void checkIdleConsolidation() {
        String mode = properties.getConsolidation().getTriggerMode();
        if ("CRON".equalsIgnoreCase(mode)) {
            return;
        }

        ConsolidationPipeline pipeline = consolidationPipelineProvider.getIfAvailable();
        if (pipeline == null) {
            return;
        }

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

        log.info("记忆模块: 空闲巩固已触发, mode={}, idleThresholdMinutes={}",
                mode, idleThreshold);
        pipeline.consolidate();
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
