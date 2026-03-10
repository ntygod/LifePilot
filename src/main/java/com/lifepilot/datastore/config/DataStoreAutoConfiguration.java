package com.lifepilot.datastore.config;

import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
import com.lifepilot.datastore.skill.DataStoreSkillProvider;
import com.lifepilot.datastore.validation.PropertyValidator;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通用数据存储模块自动配置。
 *
 * <p>注册 DataStore 模块所有核心 Bean：Repository、Engine、Validator、Manager、SkillProvider。
 * 通过 {@code lifepilot.datastore.enabled} 控制总开关，默认启用。</p>
 *
 * @author zsg
 * @since 2026-03-10
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
    public DocumentRepository documentRepository(JdbcTemplate jdbcTemplate) {
        log.info("数据存储: 注册 DocumentRepository");
        return new DocumentRepository(jdbcTemplate);
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
    public PropertyValidator propertyValidator() {
        log.info("数据存储: 注册 PropertyValidator");
        return new PropertyValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataStoreManager dataStoreManager(CollectionRepository collectionRepository,
                                              DocumentRepository documentRepository,
                                              QueryEngine queryEngine,
                                              AggregationEngine aggregationEngine,
                                              PropertyValidator propertyValidator,
                                              DataStoreProperties properties) {
        log.info("数据存储: 注册 DataStoreManager");
        return new DataStoreManager(collectionRepository, documentRepository,
                queryEngine, aggregationEngine, propertyValidator, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataStoreSkillProvider dataStoreSkillProvider(DataStoreManager dataStoreManager,
                                                         PromptRegistry promptRegistry) {
        log.info("数据存储: 注册 DataStoreSkillProvider");
        return new DataStoreSkillProvider(dataStoreManager, promptRegistry);
    }
}
