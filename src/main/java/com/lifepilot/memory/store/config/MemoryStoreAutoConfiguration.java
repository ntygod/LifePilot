package com.lifepilot.memory.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.store.vector.SqliteVecDataSource;
import com.lifepilot.memory.store.vector.SqliteVecInitializer;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.store.event.MemoryEventBus;
import com.lifepilot.memory.store.event.SpringMemoryEventBus;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxProcessor;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxRepository;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxScheduler;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.workspace.SessionWorkspaceService;
import com.lifepilot.memory.store.workspace.WorkspaceCleanupJob;
import com.lifepilot.memory.store.workspace.WorkspaceProperties;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * 记忆存储层自动装配 — 注册实体 CRUD、向量数据库、投影 outbox、工作区等核心存储组件。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration
@EnableConfigurationProperties({MemoryStoreProperties.class, WorkspaceProperties.class})
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryStoreAutoConfiguration.class);

    /**
     * 全局时钟 —— 生命周期 Listener 与再验证 / 反馈账本等时间敏感组件注入。
     *
     * <p>生产环境使用 {@link Clock#systemUTC()}；场景测试可通过 {@code @Primary}
     * 覆盖为 {@code MutableClock} 以便推进时间。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock memoryClock() {
        log.debug("记忆模块: 注册默认 Clock (systemUTC)");
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryEventBus memoryEventBus(ApplicationEventPublisher eventPublisher) {
        log.info("记忆模块: 注册 MemoryEventBus");
        return new SpringMemoryEventBus(eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqliteVecInitializer sqliteVecInitializer() {
        return new SqliteVecInitializer();
    }

    @Bean
    @ConditionalOnMissingBean(name = "vectorDataSource")
    public DataSource vectorDataSource(MemoryStoreProperties properties, SqliteVecInitializer sqliteVecInitializer) {
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
        config.setBusyTimeout(properties.getBusyTimeoutMs());
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

    @Bean
    @ConditionalOnMissingBean
    public VectorSearcher vectorSearcher(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
            @Nullable EmbeddingRouter embeddingRouter,
            MemoryStoreProperties properties) {
        boolean vecLoaded = isVecExtensionLoaded(vectorJdbcTemplate);
        log.info("记忆模块: 注册 VectorSearcher, vecExtensionLoaded={}, embeddingRouterAvailable={}, dimensions={}",
                vecLoaded, embeddingRouter != null ? "yes" : "no", properties.getEmbeddingDimensions());
        return new VectorSearcher(vectorJdbcTemplate, embeddingRouter,
                vecLoaded, properties.getEmbeddingDimensions());
    }

    @Bean
    @ConditionalOnMissingBean
    public VersionMerger versionMerger() {
        log.info("记忆模块: 注册 VersionMerger");
        return new VersionMerger();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(VectorSearcher.class)
    public ConflictDetector conflictDetector(
            JdbcTemplate jdbcTemplate,
            VectorSearcher vectorSearcher,
            GenerationRouter generationRouter,
            MemoryStoreProperties properties,
            PromptRegistry promptRegistry) {
        log.info("记忆模块: 注册 ConflictDetector, semanticMatchThreshold={}",
                properties.getSemanticMatchThreshold());
        return new ConflictDetector(jdbcTemplate, vectorSearcher, generationRouter,
                properties.getSemanticMatchThreshold(), promptRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemorySpaceRepository memorySpaceRepository(JdbcTemplate jdbcTemplate,
                                                       ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 MemorySpaceRepository");
        return new MemorySpaceRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryProjectionOutboxRepository memoryProjectionOutboxRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 MemoryProjectionOutboxRepository");
        return new MemoryProjectionOutboxRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryProjectionOutboxProcessor memoryProjectionOutboxProcessor(
            MemoryProjectionOutboxRepository repository,
            VectorSearcher vectorSearcher,
            ObjectMapper objectMapper) {
        log.info("记忆模块: 注册 MemoryProjectionOutboxProcessor");
        return new MemoryProjectionOutboxProcessor(repository, vectorSearcher, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryProjectionService memoryProjectionService(
            MemoryProjectionOutboxRepository repository,
            MemoryProjectionOutboxProcessor processor) {
        log.info("记忆模块: 注册 MemoryProjectionService");
        return new MemoryProjectionService(repository, processor);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryProjectionOutboxScheduler memoryProjectionOutboxScheduler(
            MemoryProjectionOutboxProcessor processor) {
        log.info("记忆模块: 注册 MemoryProjectionOutboxScheduler");
        return new MemoryProjectionOutboxScheduler(processor);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ConflictDetector.class, VectorSearcher.class})
    public SemanticMemory semanticMemory(
            JdbcTemplate jdbcTemplate,
            ConflictDetector conflictDetector,
            VersionMerger versionMerger,
            VectorSearcher vectorSearcher,
            MemorySpaceRepository memorySpaceRepository,
            ApplicationEventPublisher eventPublisher,
            @Nullable MemoryProjectionService projectionService) {
        log.info("记忆模块: 注册 SemanticMemory");
        var semanticMemory = new SemanticMemory(
                jdbcTemplate, conflictDetector, versionMerger, vectorSearcher, memorySpaceRepository);
        semanticMemory.setEventPublisher(eventPublisher);
        semanticMemory.setProjectionService(projectionService);
        return semanticMemory;
    }

    @Bean
    @ConditionalOnMissingBean
    public EpisodicMemory episodicMemory(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 EpisodicMemory");
        return new EpisodicMemory(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MemoryProjectionService.class)
    public ProceduralMemory proceduralMemory(
            JdbcTemplate jdbcTemplate,
            MemoryProjectionService projectionService) {
        log.info("记忆模块: 注册 ProceduralMemory");
        return new ProceduralMemory(jdbcTemplate, projectionService);
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
