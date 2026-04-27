package com.lifepilot.llm.config;

import com.lifepilot.llm.thinking.ThinkingMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM Router 配置属性绑定。
 *
 * <p>通过 {@code lifepilot.llm} 前缀绑定 YAML 配置，
 * 使用 JavaBean 风格以满足 Spring Boot 属性绑定要求。
 *
 * @author zsg
 * @since 2026-02-24
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.llm")
public class LlmConfigProperties {

    /** 总开关，默认 true。 */
    private boolean enabled = true;

    /** Provider 配置映射，键为 Provider ID。 */
    private Map<String, ProviderConfigEntry> providers = new LinkedHashMap<>();

    /** 熔断器配置。 */
    private CircuitBreakerConfigEntry circuitBreaker = new CircuitBreakerConfigEntry();

    /** 语义缓存配置。 */
    private CacheConfigEntry cache = new CacheConfigEntry();

    /** HTTP 连接池配置。 */
    private ConnectionPoolConfigEntry connectionPool = new ConnectionPoolConfigEntry();

    /**
     * 将熔断器配置条目转换为不可变 record。
     *
     * @return 熔断器配置 record
     */
    public CircuitBreakerConfig toCircuitBreakerConfig() {
        return new CircuitBreakerConfig(
                circuitBreaker.getFailureThreshold(),
                circuitBreaker.getResetTimeoutSeconds(),
                circuitBreaker.getHalfOpenMaxAttempts(),
                circuitBreaker.getRetryInitialDelayMs(),
                circuitBreaker.getRetryMultiplier(),
                circuitBreaker.getRetryMaxDelayMs()
        );
    }

    /**
     * 将 Provider 配置条目转换为不可变 ProviderConfig record。
     *
     * @param id    Provider ID（来自 Map 键）
     * @param entry 配置条目
     * @return ProviderConfig record
     */
    public static ProviderConfig toProviderConfig(String id, ProviderConfigEntry entry) {
        return new ProviderConfig(
                id,
                entry.getProfileId(),
                entry.getApiUrl(),
                entry.getApiKey(),
                entry.getModelName(),
                entry.getTimeoutSeconds(),
                entry.getPriority(),
                entry.getScenes(),
                entry.getCapabilities(),
                entry.isEnabled(),
                entry.getCostPerInputToken(),
                entry.getCostPerOutputToken(),
                entry.getMaxContextWindow(),
                entry.getEmbeddingDimension(),
                entry.isSupportsStreaming(),
                entry.isReasoning(),
                entry.getThinkingMode()
        );
    }

    /**
     * Provider 配置条目（JavaBean 风格，用于 YAML 绑定）。
     */
    @Setter
    @Getter
    public static class ProviderConfigEntry {
        private String profileId;
        private String apiUrl;
        private String apiKey;
        private String modelName;
        private int timeoutSeconds = 30;
        private int priority = 0;
        private List<String> scenes = List.of();
        private Set<ProviderCapability> capabilities = Set.of(ProviderCapability.CHAT);
        private boolean enabled = true;
        private int costPerInputToken = 0;
        private int costPerOutputToken = 0;
        private int maxContextWindow = 4096;
        private Integer embeddingDimension;
        private boolean supportsStreaming = false;
        private boolean reasoning = false;
        private ThinkingMode thinkingMode = ThinkingMode.AUTO;

    }

    /**
     * 熔断器配置条目（JavaBean 风格，用于 YAML 绑定）。
     */
    @Setter
    @Getter
    public static class CircuitBreakerConfigEntry {
        private int failureThreshold = 3;
        private int resetTimeoutSeconds = 60;
        private int halfOpenMaxAttempts = 1;
        private int retryInitialDelayMs = 500;
        private double retryMultiplier = 2.0;
        private int retryMaxDelayMs = 5000;

    }

    /**
     * 语义缓存配置条目（JavaBean 风格，用于 YAML 绑定）。
     *
     * <p>绑定 {@code lifepilot.llm.cache} 前缀。
     */
    @Setter
    @Getter
    public static class CacheConfigEntry {
        /** 缓存总开关，默认 true。 */
        private boolean enabled = true;
        /** 余弦相似度命中阈值，默认 0.92。 */
        private double similarityThreshold = 0.92;
        /** 缓存条目 TTL 秒数，默认 3600。 */
        private int ttlSeconds = 3600;
        /** 最大缓存条目数（LRU 淘汰），默认 10000。 */
        private int maxEntries = 10000;
    }

    /**
     * HTTP 连接池配置条目（JavaBean 风格，用于 YAML 绑定）。
     *
     * <p>绑定 {@code lifepilot.llm.connection-pool} 前缀。
     */
    @Setter
    @Getter
    public static class ConnectionPoolConfigEntry {
        /** 最大连接数，默认 5。 */
        private int maxConnections = 5;
        /** 连接保活时间（秒），默认 300。 */
        private int keepAliveSeconds = 300;
    }
}
