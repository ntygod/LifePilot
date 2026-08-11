package com.lifepilot.memory.consumption.config;

import com.lifepilot.memory.consumption.EpisodicCleanupJob;
import com.lifepilot.memory.consumption.attention.GraphReasoner;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.consumption.hot.HotMemoryDigestService;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
import com.lifepilot.observability.redactor.DataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 记忆消费层自动装配 — 注册热摘要、对话压缩、情景清理等组件。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration(after = MemoryStoreAutoConfiguration.class)
@EnableConfigurationProperties(MemoryConsumptionProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryConsumptionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsumptionAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public HotMemoryDigestService hotMemoryDigestService(SemanticMemory semanticMemory,
                                                          MemoryConsumptionProperties properties,
                                                          ProceduralMemory proceduralMemory,
                                                          DataRedactor dataRedactor,
                                                          Clock clock) {
        log.info("记忆模块: 注册 HotMemoryDigestService");
        return new HotMemoryDigestService(semanticMemory, properties, proceduralMemory, dataRedactor, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public EpisodicCleanupJob episodicCleanupJob(
            EpisodicMemory episodicMemory,
            JdbcTemplate jdbcTemplate,
            MemoryConsumptionProperties properties) {
        log.info("记忆模块: 注册 EpisodicCleanupJob, cron={}, retentionDays={}",
                properties.getEpisodicCleanup().getCron(),
                properties.getEpisodicCleanup().getRetentionDays());
        return new EpisodicCleanupJob(episodicMemory, jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public GraphReasoner graphReasoner(JdbcTemplate jdbcTemplate,
                                       MemoryConsumptionProperties properties,
                                       com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties retrievalProperties) {
        log.info("记忆模块: 注册 GraphReasoner, maxFanout={}, minRelationTrust={}",
                properties.getAttention().getMaxFanout(), retrievalProperties.getMinRelationTrust());
        return new GraphReasoner(jdbcTemplate, properties.getAttention().getMaxFanout(),
                retrievalProperties.getMinRelationTrust());
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryAttentionService memoryAttentionService(SemanticMemory semanticMemory,
                                                         GraphReasoner graphReasoner,
                                                         MemoryConsumptionProperties properties,
                                                         Clock clock) {
        log.info("记忆模块: 注册 MemoryAttentionService, attentionEnabled={}",
                properties.getAttention().isEnabled());
        return new MemoryAttentionService(semanticMemory, graphReasoner, properties, clock);
    }
}
