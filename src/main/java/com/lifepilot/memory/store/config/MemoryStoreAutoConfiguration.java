package com.lifepilot.memory.store.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.event.MemoryEventBus;
import com.lifepilot.memory.event.SpringMemoryEventBus;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.projection.MemoryProjectionOutboxProcessor;
import com.lifepilot.memory.projection.MemoryProjectionOutboxRepository;
import com.lifepilot.memory.projection.MemoryProjectionService;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.config.SqliteVecDataSource;
import com.lifepilot.memory.config.SqliteVecInitializer;
import com.lifepilot.memory.semantic.VersionMerger;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceCleanupJob;
import com.lifepilot.memory.workspace.WorkspaceProperties;
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
 * <p>Phase A：与旧 {@code MemoryAutoConfiguration} 并存，通过 {@code @ConditionalOnMissingBean}
 * 确保不重复注册。旧配置中的同名 Bean 优先（因为旧配置先加载），本配置作为补充。
 * Phase B 后旧配置删除 Bean 定义，本配置接管。</p>
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
}
