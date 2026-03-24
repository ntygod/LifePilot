package com.lifepilot.modelservice.model;

import com.lifepilot.llm.config.ProviderType;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模型服务存储实体。
 *
 * @author zsg
 * @since 2026-03-24
 */
public record ModelServiceEntity(
        String id,
        ModelServiceKind kind,
        ProviderType providerType,
        String apiUrl,
        @Nullable String apiKey,
        String modelName,
        int timeoutSeconds,
        int priority,
        boolean enabled,
        List<String> supportedScenes,
        Set<GenerationCapability> generationCapabilities,
        Map<String, Object> metadata,
        @Nullable String displayName,
        @Nullable String description
) {

    /**
     * 紧凑构造器，执行参数校验和防御性拷贝。
     */
    public ModelServiceEntity {
        Objects.requireNonNull(id, "服务 ID 不能为空");
        Objects.requireNonNull(kind, "服务类型不能为空");
        Objects.requireNonNull(providerType, "Provider 类型不能为空");
        Objects.requireNonNull(apiUrl, "API 地址不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
        if (priority < 0) {
            priority = 0;
        }
        supportedScenes = supportedScenes != null ? List.copyOf(supportedScenes) : List.of();
        generationCapabilities = generationCapabilities != null ? Set.copyOf(generationCapabilities) : Set.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
