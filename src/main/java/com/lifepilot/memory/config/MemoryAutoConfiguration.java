package com.lifepilot.memory.config;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.FtsSearcher;
import com.lifepilot.memory.retrieval.GraphTraverser;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.VersionMerger;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

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
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    // --- L1 工作记忆 ---

    @Bean
    @ConditionalOnMissingBean
    public TokenBudgetAllocator tokenBudgetAllocator(MemoryProperties properties) {
        log.info("记忆系统: 注册 TokenBudgetAllocator");
        return new TokenBudgetAllocator(properties);
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
            TokenBudgetAllocator allocator,
            EpisodicMemory episodicMemory) {
        log.info("记忆系统: 注册 WorkingMemory, Token 预算={}", properties.getWorkingMemoryTokenBudget());
        return new WorkingMemory(properties, allocator, episodicMemory);
    }

    // --- 向量数据库 ---

    /**
     * 向量数据库 DataSource — 独立于主数据库，用于 sqlite-vec 向量索引。
     */
    @Bean
    @ConditionalOnMissingBean(name = "vectorDataSource")
    public DataSource vectorDataSource(MemoryProperties properties) {
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
        var dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);
        log.info("记忆系统: 向量数据库初始化完成, url={}", url);
        return dataSource;
    }

    /**
     * 向量数据库 JdbcTemplate。
     */
    @Bean
    @ConditionalOnMissingBean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
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
    @ConditionalOnBean(LlmRouter.class)
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
            MemoryProperties properties) {
        log.info("记忆系统: 注册 ConflictDetector, semanticMatchThreshold={}",
                properties.getSemanticMatchThreshold());
        return new ConflictDetector(jdbcTemplate, vectorSearcher, llmRouter,
                properties.getSemanticMatchThreshold());
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
            SemanticMemory semanticMemory) {
        log.info("记忆系统: 注册 HybridRetriever");
        return new HybridRetriever(vectorSearcher, ftsSearcher, graphTraverser, semanticMemory);
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
