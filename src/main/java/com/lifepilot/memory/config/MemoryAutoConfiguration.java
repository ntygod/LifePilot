package com.lifepilot.memory.config;

import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.knowledge.rerank.Reranker;
import com.lifepilot.knowledge.rerank.RerankerConfigProvider;
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
import com.lifepilot.memory.retrieval.QueryRefiner;
import com.lifepilot.memory.retrieval.QueryRewriter;
import com.lifepilot.memory.forgetting.EntityExpirationJob;
import com.lifepilot.memory.forgetting.ForgettingEngine;
import com.lifepilot.memory.feedback.FeedbackProcessor;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.eval.store.EvalStore;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.retrieval.FtsSearcher;
import com.lifepilot.memory.retrieval.GraphTraverser;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.ExtractionValidator;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import com.lifepilot.memory.working.DefaultSlotEvictionPolicy;
import com.lifepilot.memory.working.SlotEvictionPolicy;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.memory.working.WorkingMemoryWal;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 记忆系统自动配置。
 *
 * <p>通过 {@code lifepilot.memory.enabled=true}（默认）激活，
 * 注册 L1 工作记忆、L2 情景记忆、L3 语义记忆和混合检索引擎相关 Bean。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    private final MemoryProperties properties;
    private final ObjectProvider<WorkingMemory> workingMemoryProvider;
    private final ObjectProvider<ConsolidationPipeline> consolidationPipelineProvider;

    /** 上一次空闲触发巩固的时间（防抖用）。 */
    private volatile Instant lastIdleConsolidationTime;

    public MemoryAutoConfiguration(MemoryProperties properties,
                                   ObjectProvider<WorkingMemory> workingMemoryProvider,
                                   ObjectProvider<ConsolidationPipeline> consolidationPipelineProvider) {
        this.properties = properties;
        this.workingMemoryProvider = workingMemoryProvider;
        this.consolidationPipelineProvider = consolidationPipelineProvider;
    }

    // --- L1 工作记忆 ---

    @Bean
    @ConditionalOnMissingBean
    public TokenBudgetAllocator tokenBudgetAllocator(MemoryProperties properties) {
        log.info("记忆系统: 注册 TokenBudgetAllocator");
        return new TokenBudgetAllocator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryRefiner queryRefiner(MemoryProperties properties) {
        log.info("记忆系统: 注册 QueryRefiner");
        return new QueryRefiner(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(LlmRouter.class)
    public QueryRewriter queryRewriter(LlmRouter llmRouter,
                                        MemoryProperties properties,
                                        PromptRegistry promptRegistry) {
        log.info("记忆系统: 注册 QueryRewriter, mode={}",
                properties.getRetrieval().getQueryRewriteMode());
        return new QueryRewriter(llmRouter, properties, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SlotEvictionPolicy slotEvictionPolicy() {
        log.info("记忆系统: 注册默认 SlotEvictionPolicy");
        return new DefaultSlotEvictionPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public EpisodicMemory episodicMemory(JdbcTemplate jdbcTemplate, MemoryProperties properties) {
        log.info("记忆系统: 注册 EpisodicMemory");
        return new EpisodicMemory(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkingMemoryWal workingMemoryWal(JdbcTemplate jdbcTemplate) {
        log.info("记忆系统: 注册 WorkingMemoryWal");
        return new WorkingMemoryWal(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkingMemory workingMemory(
            MemoryProperties properties,
            EpisodicMemory episodicMemory,
            TokenBudgetAllocator tokenBudgetAllocator,
            SlotEvictionPolicy slotEvictionPolicy,
            MemoryEventRecorder memoryEventRecorder,
            WorkingMemoryWal workingMemoryWal,
            @Nullable CompressionService compressionService) {
        log.info("记忆系统: 注册 WorkingMemory, Token 预算={}", properties.getWorkingMemoryTokenBudget());
        var wm = new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, workingMemoryWal, compressionService);

        // 启动时恢复：检查 WAL 表残留记录，直接 flush 到 L2
        recoverFromWal(wm, episodicMemory, workingMemoryWal);

        return wm;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EpisodicMemory.class})
    public CompressionService compressionService(EpisodicMemory episodicMemory,
                                                 LlmRouter llmRouter,
                                                 PromptRegistry promptRegistry,
                                                 MemoryProperties memoryProperties) {
        log.info("记忆系统: 注册 CompressionService");
        return new CompressionService(llmRouter, episodicMemory, promptRegistry, memoryProperties);
    }

    /**
     * 定期清理空闲会话，将其从 L1 flush 到 L2，防止工作记忆无限增长。
     *
     * <p>使用 {@link MemoryProperties#getIdleSessionTimeoutMinutes()} 作为空闲阈值。</p>
     */
    @Scheduled(fixedDelayString = "PT5M")
    public void cleanupIdleWorkingMemorySessions() {
        int timeoutMinutes = this.properties.getIdleSessionTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            return;
        }
        WorkingMemory workingMemory = this.workingMemoryProvider.getIfAvailable();
        if (workingMemory == null) {
            return;
        }
        workingMemory.cleanupIdleSessions(java.time.Duration.ofMinutes(timeoutMinutes));
    }

    /**
     * 空闲检测定时任务 — 每分钟检查一次，IDLE/HYBRID 模式下触发巩固管线。
     *
     * <p>CRON 模式下跳过空闲检测。IDLE/HYBRID 模式下检查最近用户交互时间，
     * 超过 {@code idleThresholdMinutes} 且冷却期已过时触发巩固。</p>
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void checkIdleConsolidation() {
        String mode = properties.getConsolidation().getTriggerMode();
        if ("CRON".equalsIgnoreCase(mode)) {
            return;
        }

        // 延迟获取 ConsolidationPipeline Bean（可能不存在）
        ConsolidationPipeline pipeline = consolidationPipelineProvider.getIfAvailable();
        if (pipeline == null) {
            return;
        }

        // 获取最近用户交互时间
        Instant lastInteraction = getLastInteractionTime();
        int idleThreshold = properties.getConsolidation().getIdleThresholdMinutes();
        if (Duration.between(lastInteraction, Instant.now()).toMinutes() < idleThreshold) {
            return;
        }

        // 防抖：冷却期内不重复执行
        int cooldown = properties.getConsolidation().getIdleCooldownMinutes();
        if (lastIdleConsolidationTime != null
                && Duration.between(lastIdleConsolidationTime, Instant.now()).toMinutes() < cooldown) {
            return;
        }

        log.info("空闲检测: 触发巩固管线, mode={}, 空闲时间≥{}分钟", mode, idleThreshold);
        pipeline.consolidate();
        lastIdleConsolidationTime = Instant.now();
    }

    /**
     * 获取最近一次用户交互时间。
     *
     * <p>通过 WorkingMemory 获取所有会话中最近的活动时间。
     * WorkingMemory 不可用时返回当前时间（视为非空闲）。</p>
     */
    private Instant getLastInteractionTime() {
        WorkingMemory workingMemory = workingMemoryProvider.getIfAvailable();
        if (workingMemory == null) {
            return Instant.now();
        }
        return workingMemory.getLastActivityTime();
    }

    /**
     * 系统关闭时将所有活跃 L1 会话 flush 到 L2，防止数据丢失。
     */
    @PreDestroy
    public void flushAllOnShutdown() {
        WorkingMemory workingMemory = this.workingMemoryProvider.getIfAvailable();
        if (workingMemory == null) {
            return;
        }
        log.info("系统关闭: 开始 flush 所有 L1 会话到 L2");
        workingMemory.flushAll("系统关闭");
        log.info("系统关闭: L1 会话 flush 完成");
    }

    /**
     * 启动时从 WAL 表恢复上次异常退出未 flush 的会话数据。
     *
     * <p>将残留的 WAL 记录按 session 分组，逐个 append 到 L1 后立即 flush 到 L2，
     * 恢复完成后清空 WAL 表。仅在启动时执行一次，不影响运行时性能。</p>
     */
    private void recoverFromWal(WorkingMemory workingMemory,
                                EpisodicMemory episodicMemory,
                                WorkingMemoryWal wal) {
        try {
            var pendingSessions = wal.loadPendingSessions();
            if (pendingSessions.isEmpty()) {
                return;
            }
            log.info("WAL 恢复: 检测到 {} 个未 flush 的会话，开始恢复", pendingSessions.size());
            for (var entry : pendingSessions.entrySet()) {
                String sessionId = entry.getKey();
                var slots = entry.getValue();
                try {
                    // 将 WAL 记录恢复到 L1，然后立即 flush 到 L2
                    for (var slot : slots) {
                        workingMemory.append(sessionId, slot);
                    }
                    workingMemory.flush(sessionId, "WAL 恢复");
                    log.info("WAL 恢复: sessionId={}, 槽位数={}", sessionId, slots.size());
                } catch (Exception e) {
                    log.warn("WAL 恢复失败: sessionId={}, error={}", sessionId, e.getMessage());
                }
            }
            // 恢复完成后清空 WAL 表
            wal.clearAll();
            log.info("WAL 恢复完成");
        } catch (Exception e) {
            log.warn("WAL 恢复过程异常: error={}", e.getMessage());
        }
    }

    // --- 向量数据库 ---

    @Bean
    @ConditionalOnMissingBean
    public SqliteVecInitializer sqliteVecInitializer() {
        return new SqliteVecInitializer();
    }

    /**
     * 向量数据库 DataSource — 独立于主数据库，用于 sqlite-vec 向量索引。
     */
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
                log.warn("记忆系统: 向量数据库目录创建失败, url={}", url, e);
            }
        }
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        // 允许在该 DataSource 上加载原生扩展（例如 sqlite-vec）
        config.enableLoadExtension(true);
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);
        log.info("记忆系统: 向量数据库初始化完成, url={}", url);
        // 关键：sqlite-vec 是“按连接加载”的，这里包一层确保每条连接都可用
        return new SqliteVecDataSource(dataSource, sqliteVecInitializer, "vector");
    }

    /**
     * 向量数据库 JdbcTemplate。
     */
    @Bean
    @ConditionalOnMissingBean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(Objects.requireNonNull(vectorDataSource, "vectorDataSource"));
    }

    // --- 记忆事件追踪 ---

    @Bean
    @ConditionalOnMissingBean
    public MemoryEventRecorder memoryEventRecorder(JdbcTemplate jdbcTemplate) {
        log.info("记忆系统: 注册 MemoryEventRecorder");
        return new MemoryEventRecorder(jdbcTemplate);
    }

    // --- L3 语义记忆 ---

    @Bean
    @ConditionalOnMissingBean
    public VersionMerger versionMerger() {
        log.info("记忆系统: 注册 VersionMerger");
        return new VersionMerger();
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorSearcher vectorSearcher(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            LlmRouter llmRouter,
            MemoryProperties properties) {
        boolean vecLoaded = isVecExtensionLoaded(vectorJdbcTemplate);
        log.info("记忆系统: 注册 VectorSearcher, vecExtensionLoaded={}, dimensions={}",
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
        log.info("记忆系统: 注册 ConflictDetector, semanticMatchThreshold={}",
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
        log.info("记忆系统: 注册 SemanticMemory");
        return new SemanticMemory(jdbcTemplate, conflictDetector, versionMerger, vectorSearcher);
    }

    // --- AUDN 实时实体提取 ---

    @Bean
    @ConditionalOnMissingBean
    public ExtractionValidator extractionValidator(MemoryProperties properties) {
        log.info("记忆系统: 注册 ExtractionValidator");
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
        log.info("记忆系统: 注册 RealtimeExtractor（AUDN 实时实体提取）");
        return new RealtimeExtractor(llmRouter, semanticMemory, properties, extractionValidator, jdbcTemplate, promptRegistry);
    }

    // --- 混合检索引擎 ---

    @Bean
    @ConditionalOnMissingBean
    public FtsSearcher ftsSearcher(JdbcTemplate jdbcTemplate) {
        log.info("记忆系统: 注册 FtsSearcher");
        return new FtsSearcher(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphTraverser graphTraverser(JdbcTemplate jdbcTemplate) {
        log.info("记忆系统: 注册 GraphTraverser");
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
        log.info("记忆系统: 注册 HybridRetriever, L4 意图匹配={}, Reranker={}",
                intentMatcher != null ? "启用" : "禁用",
                reranker != null ? "启用" : "禁用");
        var retriever = new HybridRetriever(vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, intentMatcher, properties, jdbcTemplate, reranker, rerankerConfigProvider);
        // 注入写入回调：记忆写入后重置 knownEmpty 短路标记，避免永久短路
        semanticMemory.setWriteCallback(retriever::resetEmptyFlag);
        episodicMemory.setWriteCallback(retriever::resetEmptyFlag);
        return retriever;
    }

    // --- L4 程序记忆 ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VectorSearcher.class)
    public ProceduralMemory proceduralMemory(
            JdbcTemplate jdbcTemplate,
            VectorSearcher vectorSearcher,
            MemoryProperties properties) {
        log.info("记忆系统: 注册 ProceduralMemory");
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
        log.info("记忆系统: 注册 IntentMatcher");
        return new IntentMatcher(proceduralMemory, vectorSearcher, jdbcTemplate, llmRouter, properties);
    }

    // --- 巩固管线 ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EpisodicMemory.class, SemanticMemory.class})
    public EpisodicToSemanticConsolidator episodicToSemanticConsolidator(
            EpisodicMemory episodicMemory,
            SemanticMemory semanticMemory,
            ObjectProvider<KnowledgeExtractionPipeline> extractionPipelineProvider,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆系统: 注册 EpisodicToSemanticConsolidator（知识提取管线按需获取）");
        return new EpisodicToSemanticConsolidator(episodicMemory, semanticMemory,
                extractionPipelineProvider, jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProceduralMemory.class})
    public EpisodicToProceduralConsolidator episodicToProceduralConsolidator(
            JdbcTemplate jdbcTemplate,
            ProceduralMemory proceduralMemory,
            LlmRouter llmRouter,
            MemoryProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆系统: 注册 EpisodicToProceduralConsolidator");
        return new EpisodicToProceduralConsolidator(jdbcTemplate, proceduralMemory,
                llmRouter, properties, promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, ProceduralMemory.class})
    public PreferenceConsolidator preferenceConsolidator(
            SemanticMemory semanticMemory,
            ProceduralMemory proceduralMemory) {
        log.info("记忆系统: 注册 PreferenceConsolidator");
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
            @Nullable com.lifepilot.memory.procedural.ProceduralMemory proceduralMemory) {
        log.info("记忆系统: 注册 ConsolidationPipeline, 偏好同步={}, 经验提升={}",
                preferenceConsolidator != null ? "启用" : "禁用",
                semanticMemory != null && proceduralMemory != null ? "启用" : "禁用");
        return new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator,
                properties, preferenceConsolidator, semanticMemory, proceduralMemory);
    }

    // --- 实体去重 ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SemanticMemory.class, VectorSearcher.class})
    public EntityDeduplicator entityDeduplicator(
            SemanticMemory semanticMemory,
            VectorSearcher vectorSearcher,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆系统: 注册 EntityDeduplicator");
        return new EntityDeduplicator(semanticMemory, vectorSearcher, jdbcTemplate, properties);
    }

    // --- 遗忘引擎 ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SemanticMemory.class)
    public ForgettingEngine forgettingEngine(
            SemanticMemory semanticMemory,
            @Nullable LlmRouter llmRouter,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆系统: 注册 ForgettingEngine, LLM={}",
                llmRouter != null ? "可用" : "不可用");
        return new ForgettingEngine(semanticMemory, llmRouter, jdbcTemplate, properties, promptRegistry);
    }

    // --- 反馈闭环 ---

    @Bean
    @ConditionalOnMissingBean
    public InjectionRecordRepository injectionRecordRepository(JdbcTemplate jdbcTemplate,
                                                               ObjectMapper objectMapper) {
        log.info("记忆系统: 注册 InjectionRecordRepository");
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
        log.info("记忆系统: 注册 FeedbackProcessor");
        return new FeedbackProcessor(injectionRecordRepository, semanticMemory,
                feedbackRepository, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityExpirationJob entityExpirationJob(JdbcTemplate jdbcTemplate) {
        log.info("记忆系统: 注册 EntityExpirationJob");
        return new EntityExpirationJob(jdbcTemplate);
    }

    // --- L2 情景记忆清理 ---

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EpisodicMemory.class)
    public EpisodicCleanupJob episodicCleanupJob(
            EpisodicMemory episodicMemory,
            JdbcTemplate jdbcTemplate,
            MemoryProperties properties) {
        log.info("记忆系统: 注册 EpisodicCleanupJob, cron={}, retentionDays={}",
                properties.getEpisodicCleanup().getCron(),
                properties.getEpisodicCleanup().getRetentionDays());
        return new EpisodicCleanupJob(episodicMemory, jdbcTemplate, properties);
    }

    // --- 经验总结 ---

    @Bean
    @ConditionalOnMissingBean
    public TrajectoryQualityAssessor trajectoryQualityAssessor(MemoryProperties properties) {
        log.info("记忆系统: 注册 TrajectoryQualityAssessor");
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
            TrajectoryQualityAssessor qualityAssessor,
            @Autowired(required = false) EvalStore evalStore) {
        log.info("记忆系统: 注册 ExperienceSummarizer");
        return new ExperienceSummarizer(semanticMemory, vectorSearcher, llmRouter,
                promptRegistry, properties, qualityAssessor, evalStore);
    }

    // --- 工具方法 ---

    /** 检测 sqlite-vec 扩展是否可用。 */
    private boolean isVecExtensionLoaded(JdbcTemplate vectorJdbcTemplate) {
        try {
            vectorJdbcTemplate.queryForObject("SELECT vec_version()", String.class);
            return true;
        } catch (Exception e) {
            log.info("记忆系统: sqlite-vec 扩展未加载，向量检索将降级为 JVM 暴力搜索");
            return false;
        }
    }
}
