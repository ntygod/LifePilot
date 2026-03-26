package com.lifepilot.rerank.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生精排客户端工厂。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class RerankClientFactory {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, RerankServiceClient> cache = new ConcurrentHashMap<>();

    public RerankClientFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 获取原生精排客户端。
     *
     * @param service 模型服务
     * @return 客户端
     */
    public RerankServiceClient getOrCreate(ModelServiceEntity service) {
        return cache.computeIfAbsent(service.id(), ignored -> create(service));
    }

    private RerankServiceClient create(ModelServiceEntity service) {
        if (service.providerType() == ProviderType.TEI) {
            return new TeiRerankClient(service, objectMapper);
        }
        throw new UnsupportedOperationException("暂不支持该原生精排服务类型: " + service.providerType());
    }
}
