package com.lifepilot.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM Router 配置属性绑定。
 *
 * <p>通过 {@code lifepilot.llm} 前缀绑定 YAML 配置，
 * 使用 JavaBean 风格以满足 Spring Boot 属性绑定要求。
 *
 * @author zsg
 * @since 2026-02-24
 */
@ConfigurationProperties(prefix = "lifepilot.llm")
public class LlmConfigProperties {

    /** 总开关，默认 true。 */
    private boolean enabled = true;

    /** Provider 配置映射，键为 Provider ID。 */
    private Map<String, ProviderConfigEntry> providers = new LinkedHashMap<>();

    /** 熔断器配置。 */
    private CircuitBreakerConfigEntry circuitBreaker = new CircuitBreakerConfigEntry();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, ProviderConfigEntry> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfigEntry> providers) {
        this.providers = providers;
    }

    public CircuitBreakerConfigEntry getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerConfigEntry circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

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
                entry.getType(),
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
                entry.isSupportsStreaming()
        );
    }

    /**
     * Provider 配置条目（JavaBean 风格，用于 YAML 绑定）。
     */
    public static class ProviderConfigEntry {
        private ProviderType type;
        private String apiUrl;
        private String apiKey;
        private String modelName;
        private int timeoutSeconds = 30;
        private int priority = 0;
        private java.util.List<String> scenes = java.util.List.of();
        private java.util.Set<ProviderCapability> capabilities = java.util.Set.of(ProviderCapability.CHAT);
        private boolean enabled = true;
        private int costPerInputToken = 0;
        private int costPerOutputToken = 0;
        private int maxContextWindow = 4096;
        private Integer embeddingDimension;
        private boolean supportsStreaming = false;

        public ProviderType getType() { return type; }
        public void setType(ProviderType type) { this.type = type; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public java.util.List<String> getScenes() { return scenes; }
        public void setScenes(java.util.List<String> scenes) { this.scenes = scenes; }
        public java.util.Set<ProviderCapability> getCapabilities() { return capabilities; }
        public void setCapabilities(java.util.Set<ProviderCapability> capabilities) { this.capabilities = capabilities; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getCostPerInputToken() { return costPerInputToken; }
        public void setCostPerInputToken(int costPerInputToken) { this.costPerInputToken = costPerInputToken; }
        public int getCostPerOutputToken() { return costPerOutputToken; }
        public void setCostPerOutputToken(int costPerOutputToken) { this.costPerOutputToken = costPerOutputToken; }
        public int getMaxContextWindow() { return maxContextWindow; }
        public void setMaxContextWindow(int maxContextWindow) { this.maxContextWindow = maxContextWindow; }
        public Integer getEmbeddingDimension() { return embeddingDimension; }
        public void setEmbeddingDimension(Integer embeddingDimension) { this.embeddingDimension = embeddingDimension; }
        public boolean isSupportsStreaming() { return supportsStreaming; }
        public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
    }

    /**
     * 熔断器配置条目（JavaBean 风格，用于 YAML 绑定）。
     */
    public static class CircuitBreakerConfigEntry {
        private int failureThreshold = 3;
        private int resetTimeoutSeconds = 60;
        private int halfOpenMaxAttempts = 1;
        private int retryInitialDelayMs = 500;
        private double retryMultiplier = 2.0;
        private int retryMaxDelayMs = 5000;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public int getResetTimeoutSeconds() { return resetTimeoutSeconds; }
        public void setResetTimeoutSeconds(int resetTimeoutSeconds) { this.resetTimeoutSeconds = resetTimeoutSeconds; }
        public int getHalfOpenMaxAttempts() { return halfOpenMaxAttempts; }
        public void setHalfOpenMaxAttempts(int halfOpenMaxAttempts) { this.halfOpenMaxAttempts = halfOpenMaxAttempts; }
        public int getRetryInitialDelayMs() { return retryInitialDelayMs; }
        public void setRetryInitialDelayMs(int retryInitialDelayMs) { this.retryInitialDelayMs = retryInitialDelayMs; }
        public double getRetryMultiplier() { return retryMultiplier; }
        public void setRetryMultiplier(double retryMultiplier) { this.retryMultiplier = retryMultiplier; }
        public int getRetryMaxDelayMs() { return retryMaxDelayMs; }
        public void setRetryMaxDelayMs(int retryMaxDelayMs) { this.retryMaxDelayMs = retryMaxDelayMs; }
    }
}
