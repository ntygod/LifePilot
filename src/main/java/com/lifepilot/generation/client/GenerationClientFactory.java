package com.lifepilot.generation.client;

import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.adapter.SpringAiProviderAdapter;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.support.ModelServiceProviderConfigMapper;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成客户端工厂。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Component
public class GenerationClientFactory {

    private final ProviderAdapterFactory providerAdapterFactory;
    private final ConcurrentHashMap<String, GenerationServiceClient> cache = new ConcurrentHashMap<>();

    public GenerationClientFactory(ProviderAdapterFactory providerAdapterFactory) {
        this.providerAdapterFactory = providerAdapterFactory;
    }

    /**
     * 获取生成客户端。
     *
     * @param service 模型服务
     * @return 客户端
     */
    public GenerationServiceClient getOrCreate(ModelServiceEntity service) {
        return cache.computeIfAbsent(service.id(), ignored -> {
            SpringAiProviderAdapter adapter = providerAdapterFactory.create(
                    ModelServiceProviderConfigMapper.toGenerationProviderConfig(service));
            return new SpringAiGenerationClient(service, adapter);
        });
    }
}
