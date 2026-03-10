package com.lifepilot.memory.config;

import com.lifepilot.knowledge.extract.KnowledgeExtractionPipeline;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.compression.CompressionService;
import com.lifepilot.memory.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.memory.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.forgetting.ForgettingEngine;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.retrieval.FtsSearcher;
import com.lifepilot.memory.retrieval.GraphTraverser;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import com.lifepilot.memory.working.DefaultSlotEvictionPolicy;
import com.lifepilot.memory.working.SlotEvictionPolicy;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
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

    public MemoryAutoConfiguration(MemoryProperties properties,
                                   ObjectProvider<WorkingMemory> workingMemoryProvider) {
        this.properties = properties;
        this.workingMemoryProvider = workingMemoryProvider;
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
    public SlotEvictionPolicy slotEvictionPolicy() {
        log.info("记忆系统: 注册默认 SlotEvictionPolicy");
        return new DefaultSlotEvictionPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public EpisodicMemory episodicMemory(JdbcTemplate jdbcTemplate) {
        log.info("记忆系统: 注册 EpisodicMemory");
        return new EpisodicMemory(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkingMemory workingMemory(
            MemoryProperties properties,
            EpisodicMemory episodicMemory,
            TokenBudgetAllocator tokenBudgetAllocator,
            SlotEvictionPolicy slotEvictionPolicy,
            MemoryEventRecorder memoryEventRecorder) {
        log.info("记忆系统: 注册 WorkingMemory, Token 预算={}", properties.getWorkingMemoryTokenBudget());
        return new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator, slotEvictionPolicy, memoryEventRecorder);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EpisodicMemory.class})
    public CompressionService compressionService(EpisodicMemory episodicMemory,
                                                 LlmRouter llmRouter,
                                                 PromptRegistry promptRegistry) {
        log.info("记忆系统: 注册 CompressionService");
        return new CompressionService(llmRouter, episodicMemory, promptRegistry);
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
            JdbcTemplate jdbcTemplate,
            LlmRouter llmRouter,
            MemoryProperties properties) {
        boolean vecLoaded = isVecExtensionLoaded(vectorJdbcTemplate);
        log.info("记忆系统: 注册 VectorSearcher, vecExtensionLoaded={}, dimensions={}",
                vecLoaded, properties.getEmbeddingDimensions());
        return new VectorSearcher(vectorJdbcTemplate, jdbcTemplate, llmRouter,
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
    @ConditionalOnBean({SemanticMemory.class})
    public RealtimeExtractor realtimeExtractor(LlmRouter llmRouter,
                                               SemanticMemory semanticMemory) {
        log.info("记忆系统: 注册 RealtimeExtractor（AUDN 实时实体提取）");
        return new RealtimeExtractor(llmRouter, semanticMemory, properties);
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
            @Nullable IntentMatcher intentMatcher) {
        log.info("记忆系统: 注册 HybridRetriever, L4 意图匹配={}",
                intentMatcher != null ? "启用" : "禁用");
        var retriever = new HybridRetriever(vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, intentMatcher);
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
            MemoryProperties properties) {
        log.info("记忆系统: 注册 EpisodicToProceduralConsolidator");
        return new EpisodicToProceduralConsolidator(jdbcTemplate, proceduralMemory,
                llmRouter, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EpisodicToSemanticConsolidator.class, EpisodicToProceduralConsolidator.class})
    public ConsolidationPipeline consolidationPipeline(
            EpisodicToSemanticConsolidator semanticConsolidator,
            EpisodicToProceduralConsolidator proceduralConsolidator,
            MemoryProperties properties) {
        log.info("记忆系统: 注册 ConsolidationPipeline");
        return new ConsolidationPipeline(semanticConsolidator, proceduralConsolidator, properties);
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
