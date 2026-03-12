package com.lifepilot.llm.config;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * LLM Provider 配置。
 *
 * <p>不可变数据载体，紧凑构造器中执行参数校验和防御性拷贝。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record ProviderConfig(
        String id,
        ProviderType type,
        String apiUrl,
        @Nullable String apiKey,
        String modelName,
        int timeoutSeconds,
        int priority,
        List<String> scenes,
        Set<ProviderCapability> capabilities,
        boolean enabled,
        int costPerInputToken,
        int costPerOutputToken,
        int maxContextWindow,
        @Nullable Integer embeddingDimension,
        boolean supportsStreaming
) {

    /**
     * 紧凑构造器 — 参数校验 + 防御性拷贝。
     */
    public ProviderConfig {
        Objects.requireNonNull(id, "Provider ID 不能为空");
        Objects.requireNonNull(type, "Provider 类型不能为空");
        Objects.requireNonNull(apiUrl, "API URL 不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (priority < 0) priority = 0;
        scenes = List.copyOf(scenes != null ? scenes : List.of());
        capabilities = capabilities != null
                ? Set.copyOf(capabilities) : Set.of(ProviderCapability.CHAT);
    }

    /**
     * 是否为本地模型（Ollama）。
     *
     * @return 本地模型返回 true
     */
    public boolean isLocal() {
        return type == ProviderType.OLLAMA;
    }

    /**
     * 是否具备指定能力。
     *
     * @param cap 能力枚举
     * @return 具备返回 true
     */
    public boolean hasCapability(ProviderCapability cap) {
        return capabilities.contains(cap);
    }

    /**
     * 是否支持指定场景。
     *
     * @param scene 场景名称
     * @return 支持返回 true
     */
    public boolean supportsScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return false;
        }
        String expectedScene = scene.trim();
        return scenes.stream()
                .map(String::trim)
                .anyMatch(expectedScene::equals);
    }

    /**
     * 估算请求成本（分）。本地模型返回 0。
     *
     * @param inputTokens  输入 Token 数
     * @param outputTokens 输出 Token 数
     * @return 估算成本
     */
    public int estimateCost(int inputTokens, int outputTokens) {
        if (isLocal()) return 0;
        return (int) ((long) inputTokens * costPerInputToken / 1_000_000
                + (long) outputTokens * costPerOutputToken / 1_000_000);
    }
}
