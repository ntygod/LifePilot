package com.lifepilot.memory.governance.config;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.governance.MemoryAccessPolicy;
import com.lifepilot.memory.mcp.server.MemoryMcpHandler;
import com.lifepilot.memory.mcp.server.MemoryMcpToolRegistry;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.security.MemoryInjectionDetector;
import com.lifepilot.memory.security.PromptInjectionPatternScanner;
import com.lifepilot.memory.security.SpaceTrustDistribution;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆治理层自动装配 — 注册访问策略、安全检测、审计、MCP Server 等治理组件。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration(after = MemoryStoreAutoConfiguration.class)
@EnableConfigurationProperties({MemoryGovernanceProperties.class, MemoryProperties.class})
@ConditionalOnProperty(prefix = "lifepilot.memory", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryGovernanceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryGovernanceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public MemoryAccessPolicy memoryAccessPolicy() {
        log.info("记忆模块: 注册 MemoryAccessPolicy");
        return new MemoryAccessPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryEventRecorder memoryEventRecorder(JdbcTemplate jdbcTemplate) {
        log.info("记忆模块: 注册 MemoryEventRecorder");
        return new MemoryEventRecorder(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptInjectionPatternScanner promptInjectionPatternScanner() {
        return new PromptInjectionPatternScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpaceTrustDistribution spaceTrustDistribution(MemoryProperties properties) {
        return new SpaceTrustDistribution(
                properties.getSecurity().getSampleWindowSize());
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryInjectionDetector memoryInjectionDetector(
            PromptInjectionPatternScanner scanner,
            SpaceTrustDistribution distribution,
            MemoryProperties properties) {
        return new MemoryInjectionDetector(scanner, distribution, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.mcp-server.enabled", havingValue = "true")
    public MemoryMcpToolRegistry memoryMcpToolRegistry() {
        return new MemoryMcpToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "lifepilot.memory.mcp-server.enabled", havingValue = "true")
    public MemoryMcpHandler memoryMcpHandler(
            MemoryMcpToolRegistry toolRegistry,
            MemoryProperties properties,
            @Nullable HybridRetriever hybridRetriever,
            @Nullable EpisodicMemory episodicMemory,
            @Nullable SemanticMemory semanticMemory) {
        return new MemoryMcpHandler(
                toolRegistry, properties, hybridRetriever, episodicMemory, semanticMemory);
    }
}
