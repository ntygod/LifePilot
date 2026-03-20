package com.lifepilot.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.knowledge.rerank.Reranker;
import com.lifepilot.knowledge.rerank.RerankerConfigProvider;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.compression.CompressionService;
import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.consolidation.EntityDeduplicator;
import com.lifepilot.memory.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.memory.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.memory.consolidation.PreferenceConsolidator;
import com.lifepilot.memory.episodic.EpisodicCleanupJob;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.experience.ExperienceSummarizer;
import com.lifepilot.memory.experience.TrajectoryQualityAssessor;
import com.lifepilot.memory.feedback.FeedbackProcessor;
import com.lifepilot.memory.forgetting.EntityExpirationJob;
import com.lifepilot.memory.forgetting.ForgettingEngine;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.retrieval.FtsSearcher;
import com.lifepilot.memory.retrieval.GraphTraverser;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.retrieval.QueryRefiner;
import com.lifepilot.memory.retrieval.QueryRewriter;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.ExtractionValidator;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceCleanupJob;
import com.lifepilot.memory.workspace.WorkspaceProperties;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@AutoConfiguration
@EnableConfigurationProperties({MemoryProperties.class, WorkspaceProperties.class})
@EnableScheduling
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    private final MemoryProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ConsolidationPipeline> consolidationPipelineProvider;

    private volatile Instant lastIdleConsolidationTime;

    public MemoryAutoConfiguration(MemoryProperties properties,
                                   JdbcTemplate jdbcTemplate,
                                   ObjectProvider<ConsolidationPipeline> consolidationPipelineProvider) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.consolidationPipelineProvider = consolidationPipelineProvider;
    }

    // L1 临时工作区

    @Bean
    @ConditionalOnMissingBean
    public QueryRefiner queryRefiner(MemoryProperties properties) {
        log.info("记忆模块: 注册 QueryRefiner");
        return new QueryRefiner(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(LlmRouter.class)
    public QueryRewriter queryRewriter(LlmRouter llmRouter,
                                       MemoryProperties properties,
                                       PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 QueryRewriter, mode={}",
                properties.getRetrieval().getQueryRewriteMode());
        return new QueryRewriter(llmRouter, properties, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.memory.workspace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SessionWorkspaceService sessionWorkspaceService(JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper,
                                                           WorkspaceProperties workspaceProperties) {
        log.info("记忆模块: 注册 SessionWorkspaceService");
        return new SessionWorkspaceService(jdbcTemplate, objectMapper, workspaceProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SessionWorkspaceService.class)
    public WorkspaceCleanupJob workspaceCleanupJob(SessionWorkspaceService workspaceService) {
        log.info("记忆模块: 注册 WorkspaceCleanupJob");
        return new WorkspaceCleanupJob(workspaceService);
    }

    // L2 对话回忆与检索

    @Bean
    @ConditionalOnMissingBean
    public EpisodicMemory episodicMemory(JdbcTemplate jdbcTemplate, MemoryProperties properties) {
        log.info("记忆模块: 注册 EpisodicMemory");
        return new EpisodicMemory(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EpisodicMemory.class)
    public CompressionService compressionService(EpisodicMemory episodicMemory,
                                                 LlmRouter llmRouter,
                                                 PromptRegistry promptRegistry,
                                                 MemoryProperties memoryProperties) {
        log.info("记忆模块: 注册 CompressionService");
        return new CompressionService(llmRouter, episodicMemory, promptRegistry, memoryProperties);
    }

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
                    "SELECT MAX(created_at) FROM chat_messages",
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

    // 向量数据库

    @Bean
    @ConditionalOnMissingBean
    public SqliteVecInitializer sqliteVecInitializer() {
        return new SqliteVecInitializer();
    }

    @Bean
    @ConditionalOnMissingBean(name = "vectorDataSource")
    public DataSource vectorDataSource(MemoryProperties properties, SqliteVecInitializer sqliteVecInitializer) {
        String url = properties.getVectorDbUrl();
        if (!url.contains(":memory:") && !url.contains("mode=memory")) {
            try {
                var dbPath = url.replace("jdbc:sqlite:", "");
                var parentDir = Path.of(dbPath).getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }
            } catch (Exception e) {
                log.warn("记忆模块: 创建向量数据库目录失败, url={}", url, e);
            }
        }
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        config.enableLoadExtension(true);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);
        log.info("记忆模块: 向量数据库已就绪, url={}", url);
        return new SqliteVecDataSource(dataSource, sqliteVecInitializer, "vector");
    }

    @Bean
    @ConditionalOnMissingBean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(Objects.requireNonNull(vectorDataSource, "vectorDataSource"));
    }

    // 事件与审计

    @Bean
    @ConditionalOnMissingBean
    public MemoryEventRecorder memoryEventRecorder(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 MemoryEventRecorder");
        return new MemoryEventRecorder(jdbcTemplate);
    }

    // L3 语义记忆

    @Bean
    @ConditionalOnMissingBean
    public VersionMerger versionMerger() {
        log.info("记忆模块: 注册 VersionMerger");
        return new VersionMerger();
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorSearcher vectorSearcher(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            LlmRouter llmRouter,
            MemoryProperties properties) {
        boolean vecLoaded = isVecExtensionLoaded(vectorJdbcTemplate);
        log.info("记忆模块: 注册 VectorSearcher, vecExtensionLoaded={}, dimensions={}",
                vecLoaded, properties.getEmbeddingDimensions());
        return new VectorSearcher(vectorJdbcTemplate, llmRouter,
                vecLoaded, properties.getEmbeddingDimensions());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VectorSearcher.class)
    public ConflictDetector conflictDetector(
            JdbcTemplate jdbcTemplate,
            VectorSearcher vectorSearcher,
            @Nullable LlmRouter llmRouter,
            MemoryProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 ConflictDetector, semanticMatchThreshold={}",
                properties.getSemanticMatchThreshold());
        return new ConflictDetector(jdbcTemplate, vectorSearcher, llmRouter,
                properties.getSemanticMatchThreshold(), promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConflictDetector.class, VectorSearcher.class})
    public SemanticMemory semanticMemory(
            JdbcTemplate jdbcTemplate,
            ConflictDetector conflictDetector,
            VersionMerger versionMerger,
            VectorSearcher vectorSearcher) {
        log.info("记忆模块: 注册 SemanticMemory");
        return new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExtractionValidator extractionValidator(MemoryProperties properties) {
        log.info("记忆模块: 注册 ExtractionValidator");
        return new ExtractionValidator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, ExtractionValidator.class})
    public RealtimeExtractor realtimeExtractor(LlmRouter llmRouter,
                                               SemanticMemory semanticMemory,
                                               ExtractionValidator extractionValidator,
                                               JdbcTemplate jdbcTemplate,
                                               PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 RealtimeExtractor");
        return new RealtimeExtractor(llmRouter, semanticMemory, properties, extractionValidator, jdbcTemplate, promptRegistry);
    }

    // 检索

    @Bean
    @ConditionalOnMissingBean
    public FtsSearcher ftsSearcher(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 FtsSearcher");
        return new FtsSearcher(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphTraverser graphTraverser(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 GraphTraverser");
        return new GraphTraverser(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public HybridRetriever hybridRetriever(
            VectorSearcher vectorSearcher,
            FtsSearcher ftsSearcher,
            GraphTraverser graphTraverser,
            SemanticMemory semanticMemory,
            EpisodicMemory episodicMemory,
            @Nullable IntentMatcher intentMatcher,
            @Nullable Reranker reranker,
            @Nullable RerankerConfigProvider rerankerConfigProvider,
            JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 HybridRetriever, intentMatcher={}, reranker={}",
                intentMatcher != null ? "enabled" : "disabled",
                reranker != null ? "enabled" : "disabled");
        var retriever = new HybridRetriever(vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, intentMatcher, properties, jdbcTemplate, reranker, rerankerConfigProvider);
        semanticMemory.setWriteCallback(retriever::resetEmptyFlag);
        episodicMemory.setWriteCallback(retriever::resetEmptyFlag);
        return retriever;
    }

    // L4 程序记忆

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VectorSearcher.class)
    public ProceduralMemory proceduralMemory(
            JdbcTemplate jdbcTemplate,
            VectorSearcher vectorSearcher,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 ProceduralMemory");
        return new ProceduralMemory(jdbcTemplate, vectorSearcher, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProceduralMemory.class, VectorSearcher.class})
    public IntentMatcher intentMatcher(
            ProceduralMemory proceduralMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            LlmRouter llmRouter,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 IntentMatcher");
        return new IntentMatcher(proceduralMemory, vectorSearcher, jdbcTemplate, llmRouter, properties);
    }

    // 巩固

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EpisodicMemory.class, SemanticMemory.class})
    public EpisodicToSemanticConsolidator episodicToSemanticConsolidator(
            EpisodicMemory episodicMemory,
            SemanticMemory semanticMemory,
            ObjectProvider<KnowledgeExtractionPipeline> extractionPipelineProvider,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 EpisodicToSemanticConsolidator");
        return new EpisodicToSemanticConsolidator(episodicMemory, semanticMemory,
                extractionPipelineProvider, jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProceduralMemory.class)
    public EpisodicToProceduralConsolidator episodicToProceduralConsolidator(
            JdbcTemplate jdbcTemplate,
            ProceduralMemory proceduralMemory,
            LlmRouter llmRouter,
            MemoryProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 EpisodicToProceduralConsolidator");
        return new EpisodicToProceduralConsolidator(jdbcTemplate, proceduralMemory,
                llmRouter, properties, promptRegistry);
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
    @ConditionalOnBean({EpisodicToSemanticConsolidator.class, EpisodicToProceduralConsolidator.class})
    public ConsolidationPipeline consolidationPipeline(
            EpisodicToSemanticConsolidator semanticConsolidator,
            EpisodicToProceduralConsolidator proceduralConsolidator,
            MemoryProperties properties,
            @Nullable PreferenceConsolidator preferenceConsolidator,
            @Nullable SemanticMemory semanticMemory,
            @Nullable ProceduralMemory proceduralMemory,
            @Nullable com.lifepilot.memory.consolidation.ExperienceMerger experienceMerger) {
        log.info("记忆模块: 注册 ConsolidationPipeline, preferenceSync={}, experienceLift={}, experienceMerge={}",
                preferenceConsolidator != null ? "enabled" : "disabled",
                semanticMemory != null && proceduralMemory != null ? "enabled" : "disabled",
                experienceMerger != null ? "enabled" : "disabled");
        return new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator,
                properties, preferenceConsolidator, semanticMemory, proceduralMemory,
                experienceMerger);
    }

    // 遗忘 / 反馈 / 清理

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public EntityDeduplicator entityDeduplicator(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 EntityDeduplicator");
        return new EntityDeduplicator(semanticMemory, vectorSearcher, jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public ForgettingEngine forgettingEngine(
            SemanticMemory semanticMemory,
            @Nullable LlmRouter llmRouter,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 ForgettingEngine, llmAvailable={}",
                llmRouter != null ? "yes" : "no");
        return new ForgettingEngine(semanticMemory, llmRouter, jdbcTemplate, properties, promptRegistry);
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
    public EntityExpirationJob entityExpirationJob(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 EntityExpirationJob");
        return new EntityExpirationJob(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EpisodicMemory.class)
    public EpisodicCleanupJob episodicCleanupJob(
            EpisodicMemory episodicMemory,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 EpisodicCleanupJob, cron={}, retentionDays={}",
                properties.getEpisodicCleanup().getCron(),
                properties.getEpisodicCleanup().getRetentionDays());
        return new EpisodicCleanupJob(episodicMemory, jdbcTemplate, properties);
    }

    // 经验学习

    @Bean
    @ConditionalOnMissingBean
    public TrajectoryQualityAssessor trajectoryQualityAssessor(MemoryProperties properties) {
        log.info("记忆模块: 注册 TrajectoryQualityAssessor");
        return new TrajectoryQualityAssessor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public ExperienceSummarizer experienceSummarizer(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            LlmRouter llmRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties,
            TrajectoryQualityAssessor qualityAssessor) {
        log.info("记忆模块: 注册 ExperienceSummarizer");
        return new ExperienceSummarizer(semanticMemory, vectorSearcher, llmRouter,
                promptRegistry, properties, qualityAssessor);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public com.lifepilot.memory.experience.EffectivenessTracker effectivenessTracker(
            SemanticMemory semanticMemory,
            InjectionRecordRepository injectionRecordRepository,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 EffectivenessTracker");
        return new com.lifepilot.memory.experience.EffectivenessTracker(
                semanticMemory, injectionRecordRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public com.lifepilot.memory.experience.ContrastiveLearner contrastiveLearner(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            LlmRouter llmRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 ContrastiveLearner");
        return new com.lifepilot.memory.experience.ContrastiveLearner(
                semanticMemory, vectorSearcher, llmRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public com.lifepilot.memory.experience.SubtaskReflector subtaskReflector(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            LlmRouter llmRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 SubtaskReflector");
        return new com.lifepilot.memory.experience.SubtaskReflector(
                semanticMemory, vectorSearcher, llmRouter, promptRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public com.lifepilot.memory.consolidation.ExperienceMerger experienceMerger(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            LlmRouter llmRouter,
            PromptRegistry promptRegistry,
            MemoryProperties properties) {
        log.info("记忆模块: 注册 ExperienceMerger");
        return new com.lifepilot.memory.consolidation.ExperienceMerger(
                semanticMemory, vectorSearcher, llmRouter, promptRegistry, properties);
    }

    private boolean isVecExtensionLoaded(JdbcTemplate vectorJdbcTemplate) {
        try {
            vectorJdbcTemplate.queryForObject("SELECT vec_version()", String.class);
            return true;
        } catch (Exception e) {
            log.info("记忆模块: 未加载 sqlite-vec 扩展，回退到 JVM 向量检索");
            return false;
        }
    }
}
