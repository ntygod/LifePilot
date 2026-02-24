package com.lifepilot.memory.config;

import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆系统自动配置。
 *
 * <p>通过 {@code lifepilot.memory.enabled=true}（默认）激活，
 * 注册 TokenBudgetAllocator、EpisodicMemory、WorkingMemory Bean。</p>
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
}
