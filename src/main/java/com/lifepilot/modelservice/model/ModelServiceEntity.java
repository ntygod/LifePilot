package com.lifepilot.modelservice.model;

import com.lifepilot.llm.thinking.ThinkingMode;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模型服务存储实体。
 *
 * <p>profileId 替代旧 providerType，作为协议路由主键；
 * is_reasoning + thinking_mode 控制推理模型行为。
 *
 * @author zsg
 * @since 2026-04-27
 */
public record ModelServiceEntity(
        String id,
        ModelServiceKind kind,
        String profileId,
        String apiUrl,
        @Nullable String apiKey,
        String modelName,
        int timeoutSeconds,
        int priority,
        boolean enabled,
        boolean isReasoning,
        ThinkingMode thinkingMode,
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
        Objects.requireNonNull(profileId, "Profile ID 不能为空");
        Objects.requireNonNull(apiUrl, "API 地址不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        Objects.requireNonNull(thinkingMode, "thinking_mode 不能为空");
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
