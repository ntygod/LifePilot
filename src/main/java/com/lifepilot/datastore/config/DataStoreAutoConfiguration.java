package com.lifepilot.datastore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.ingest.DocumentIngester;
import com.lifepilot.knowledge.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/**
 * 通用数据存储模块自动配置。
 *
 * <p>注册 DataStore 模块所有核心 Bean：Repository、Engine、Manager。
 * 通过 {@code lifepilot.datastore.enabled} 控制总开关，默认启用。</p>
 *
 * @author zsg
 * @since 2026-04-13
 */
@AutoConfiguration
@EnableConfigurationProperties(DataStoreProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.datastore", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DataStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataStoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public CollectionRepository collectionRepository(JdbcTemplate jdbcTemplate) {
        log.info("数据存储: 注册 CollectionRepository");
        return new CollectionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryEngine queryEngine(DataStoreProperties properties) {
        log.info("数据存储: 注册 QueryEngine");
        return new QueryEngine(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AggregationEngine aggregationEngine() {
        log.info("数据存储: 注册 AggregationEngine");
        return new AggregationEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataStoreManager dataStoreManager(CollectionRepository collectionRepository,
                                              QueryEngine queryEngine,
                                              AggregationEngine aggregationEngine,
                                              DataStoreProperties properties,
                                              ObjectMapper objectMapper,
                                              @Nullable KnowledgeBaseManager knowledgeBaseManager,
                                              @Nullable DocumentIngester documentIngester,
                                              @Nullable DocumentRepository knowledgeDocumentRepository,
                                              @Nullable DatastoreKnowledgeBaseProvisioner datastoreKnowledgeBaseProvisioner) {
        log.info("数据存储: 注册 DataStoreManager");
        return new DataStoreManager(collectionRepository,
                queryEngine, aggregationEngine, properties,
                objectMapper, knowledgeBaseManager, documentIngester,
                knowledgeDocumentRepository, datastoreKnowledgeBaseProvisioner);
    }
}
