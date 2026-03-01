package com.lifepilot.llm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * LLM Provider 数据库实体。
 *
 * <p>用于数据库存储和读取，包含 JSON 字段的序列化/反序列化逻辑。
 *
 * @author zsg
 * @since 2026-02-27
 */
public record LlmProviderEntity(
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
        boolean supportsStreaming,
        boolean isPreset,
        @Nullable String displayName,
        @Nullable String description
) {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 紧凑构造器：参数校验和默认值设置。
     */
    public LlmProviderEntity {
        Objects.requireNonNull(id, "Provider ID 不能为空");
        Objects.requireNonNull(type, "Provider 类型不能为空");
        Objects.requireNonNull(apiUrl, "API URL 不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (priority < 0) priority = 0;
        scenes = scenes != null ? List.copyOf(scenes) : List.of();
        capabilities = capabilities != null
                ? Set.copyOf(capabilities) : Set.of(ProviderCapability.CHAT);
    }

    /**
     * 转换为 ProviderConfig record。
     *
     * @return ProviderConfig
     */
    public ProviderConfig toProviderConfig() {
        return new ProviderConfig(
                id,
                type,
                apiUrl,
                apiKey,
                modelName,
                timeoutSeconds,
                priority,
                scenes,
                capabilities,
                enabled,
                costPerInputToken,
                costPerOutputToken,
                maxContextWindow,
                embeddingDimension,
                supportsStreaming
        );
    }

    /**
     * 从数据库行创建实体（处理 JSON 字段）。
     *
     * @param id                  Provider ID
     * @param type                Provider 类型字符串
     * @param apiUrl              API URL
     * @param apiKey              API Key（可为空）
     * @param modelName           模型名称
     * @param timeoutSeconds      超时秒数
     * @param priority            优先级
     * @param scenesJson         场景 JSON 字符串
     * @param capabilitiesJson   能力 JSON 字符串
     * @param enabled             是否启用
     * @param costPerInputToken   每输入 Token 成本
     * @param costPerOutputToken  每输出 Token 成本
     * @param maxContextWindow    最大上下文窗口
     * @param embeddingDimension  Embedding 维度（可为空）
     * @param supportsStreaming   是否支持流式输出
     * @param isPreset           是否为预设置
     * @param displayName         显示名称（可为空）
     * @param description         描述（可为空）
     * @return LlmProviderEntity
     */
    public static LlmProviderEntity fromRow(
            String id,
            String type,
            String apiUrl,
            @Nullable String apiKey,
            String modelName,
            int timeoutSeconds,
            int priority,
            String scenesJson,
            String capabilitiesJson,
            boolean enabled,
            int costPerInputToken,
            int costPerOutputToken,
            int maxContextWindow,
            @Nullable Integer embeddingDimension,
            boolean supportsStreaming,
            boolean isPreset,
            @Nullable String displayName,
            @Nullable String description
    ) {
        try {
            List<String> scenes = objectMapper.readValue(
                    scenesJson, new TypeReference<List<String>>() {});
            List<String> capabilityNames = objectMapper.readValue(
                    capabilitiesJson, new TypeReference<List<String>>() {});
            Set<ProviderCapability> capabilities = capabilityNames.stream()
                    .map(ProviderCapability::valueOf)
                    .collect(java.util.stream.Collectors.toSet());

            return new LlmProviderEntity(
                    id,
                    ProviderType.valueOf(type),
                    apiUrl,
                    apiKey,
                    modelName,
                    timeoutSeconds,
                    priority,
                    scenes,
                    capabilities,
                    enabled,
                    costPerInputToken,
                    costPerOutputToken,
                    maxContextWindow,
                    embeddingDimension,
                    supportsStreaming,
                    isPreset,
                    displayName,
                    description
            );
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "解析 Provider 配置失败: id=" + id + ", error=" + e.getMessage(), e);
        }
    }

    /**
     * 转换为数据库插入/更新参数。
     *
     * @return 参数数组
     */
    public Object[] toRowParams() {
        try {
            String scenesJson = objectMapper.writeValueAsString(scenes);
            String capabilitiesJson = objectMapper.writeValueAsString(
                    capabilities.stream().map(Enum::name).toList());
            return new Object[]{
                    id,
                    type.name(),
                    apiUrl,
                    apiKey,
                    modelName,
                    timeoutSeconds,
                    priority,
                    scenesJson,
                    capabilitiesJson,
                    enabled ? 1 : 0,
                    costPerInputToken,
                    costPerOutputToken,
                    maxContextWindow,
                    embeddingDimension,
                    supportsStreaming ? 1 : 0,
                    isPreset ? 1 : 0,
                    displayName,
                    description
            };
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "序列化 Provider 配置失败: id=" + id + ", error=" + e.getMessage(), e);
        }
    }
}
