package com.lifepilot.embedding.client;

import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.adapter.SpringAiProviderAdapter;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.support.ModelServiceProviderConfigMapper;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量客户端工厂。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class EmbeddingClientFactory {

    private final ProviderAdapterFactory providerAdapterFactory;
    private final ConcurrentHashMap<String, EmbeddingServiceClient> cache = new ConcurrentHashMap<>();

    public EmbeddingClientFactory(ProviderAdapterFactory providerAdapterFactory) {
        this.providerAdapterFactory = providerAdapterFactory;
    }

    /**
     * 获取向量客户端。
     *
     * @param service 模型服务
     * @return 客户端
     */
    public EmbeddingServiceClient getOrCreate(ModelServiceEntity service) {
        return cache.computeIfAbsent(service.id(), ignored -> {
            SpringAiProviderAdapter adapter = providerAdapterFactory.create(
                    ModelServiceProviderConfigMapper.toEmbeddingProviderConfig(service));
            return new SpringAiEmbeddingClient(adapter);
        });
    }
}
