package com.lifepilot.modelservice.support;

import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import org.springframework.lang.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 模型服务到 ProviderConfig 的映射器。
 *
 * <p>用于复用现有 Spring AI 适配器工厂，避免在生成与向量链重复构建底层客户端。
 *
 * @author zsg
 * @since 2026-03-24
 */
public final class ModelServiceProviderConfigMapper {

    private ModelServiceProviderConfigMapper() {
    }

    /**
     * 将生成服务映射为 ProviderConfig。
     *
     * @param service 生成服务
     * @return ProviderConfig
     */
    public static ProviderConfig toGenerationProviderConfig(ModelServiceEntity service) {
        Set<ProviderCapability> capabilities = EnumSet.noneOf(ProviderCapability.class);
        for (GenerationCapability capability : service.generationCapabilities()) {
            capabilities.add(mapGenerationCapability(capability));
        }
        return new ProviderConfig(
                service.id(),
                service.providerType(),
                service.apiUrl(),
                service.apiKey(),
                service.modelName(),
                service.timeoutSeconds(),
                service.priority(),
                service.supportedScenes(),
                capabilities,
                service.enabled(),
                0,
                0,
                0,
                intMetadata(service, "embeddingDimension"),
                capabilities.contains(ProviderCapability.STREAMING));
    }

    /**
     * 将向量服务映射为 ProviderConfig。
     *
     * @param service 向量服务
     * @return ProviderConfig
     */
    public static ProviderConfig toEmbeddingProviderConfig(ModelServiceEntity service) {
        return new ProviderConfig(
                service.id(),
                service.providerType(),
                service.apiUrl(),
                service.apiKey(),
                service.modelName(),
                service.timeoutSeconds(),
                service.priority(),
                List.of(),
                Set.of(ProviderCapability.EMBEDDING),
                service.enabled(),
                0,
                0,
                0,
                intMetadata(service, "embeddingDimension"),
                false);
    }

    private static ProviderCapability mapGenerationCapability(GenerationCapability capability) {
        return switch (capability) {
            case CHAT -> ProviderCapability.CHAT;
            case STRUCTURED_OUTPUT -> ProviderCapability.STRUCTURED_OUTPUT;
            case FUNCTION_CALLING -> ProviderCapability.FUNCTION_CALLING;
            case STREAMING -> ProviderCapability.STREAMING;
            case VISION -> ProviderCapability.VISION;
            case NATIVE_AUDIO -> ProviderCapability.NATIVE_AUDIO;
            case NATIVE_VIDEO -> ProviderCapability.NATIVE_VIDEO;
        };
    }

    @Nullable
    private static Integer intMetadata(ModelServiceEntity service, String key) {
        Object value = service.metadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
