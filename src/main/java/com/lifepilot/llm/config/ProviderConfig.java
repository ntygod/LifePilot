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
        String profileId,
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
     *
     * <p>{@code profileId} 在 Phase 3 阶段由所有构造点临时硬编码默认值（按 {@link ProviderType}
     * 推断），Phase 6 起由 ModelService 注入用户实选的 profile id。新旧字段在重构窗口期共存。
     */
    public ProviderConfig {
        Objects.requireNonNull(id, "Provider ID 不能为空");
        Objects.requireNonNull(type, "Provider 类型不能为空");
        Objects.requireNonNull(profileId, "Profile ID 不能为空");
        Objects.requireNonNull(apiUrl, "API URL 不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (priority < 0) priority = 0;
        scenes = List.copyOf(scenes != null ? scenes : List.of());
        capabilities = capabilities != null
                ? Set.copyOf(capabilities) : Set.of(ProviderCapability.CHAT);
    }

    /**
     * 根据旧 ProviderType 返回临时默认 profileId。
     *
     * <p><b>Phase 3 临时方案</b>：所有 ProviderConfig 构造点在 Phase 6 完整迁移
     * 到由 ModelService.profileId 提供真实值之前，借此方法做兜底映射。
     *
     * <p><b>本方法将在 Phase 6 删除</b>，不应在新代码中调用。
     *
     * @param type 旧 ProviderType
     * @return 默认 profileId
     * @deprecated 仅 Phase 3-5 期间使用，Phase 6 删除（届时 ModelService 直接提供 profileId）
     */
    @Deprecated(forRemoval = true, since = "Phase 3")
    public static String defaultProfileIdFor(ProviderType type) {
        return switch (type) {
            case OPENAI_COMPATIBLE -> "openai-official";
            case ANTHROPIC -> "anthropic-official";
            case OLLAMA -> "ollama-local";
            case TEI -> "tei-local";
        };
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
