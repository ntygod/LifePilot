package com.lifepilot.modelservice.service;

import com.lifepilot.embedding.client.EmbeddingClientFactory;
import com.lifepilot.generation.client.GenerationClientFactory;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.support.ModelServiceProviderConfigMapper;
import com.lifepilot.rerank.client.RerankClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型服务运行时注册服务。
 *
 * <p>负责将 {@code model_services} 中可参与生成与向量调用的服务同步到 {@link ProviderRegistry}，
 * 让旧生成链和多模态链路都直接消费新的模型服务表。</p>
 *
 * @author zsg
 * @since 2026-03-24
 */
@Service
@ConditionalOnBean(ProviderRegistry.class)
public class ModelServiceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceRegistrationService.class);

    private final ModelServiceRepository modelServiceRepository;
    private final ProviderRegistry providerRegistry;
    @Nullable
    private final GenerationClientFactory generationClientFactory;
    @Nullable
    private final EmbeddingClientFactory embeddingClientFactory;
    @Nullable
    private final RerankClientFactory rerankClientFactory;

    public ModelServiceRegistrationService(ModelServiceRepository modelServiceRepository,
                                           ProviderRegistry providerRegistry,
                                           @Nullable GenerationClientFactory generationClientFactory,
                                           @Nullable EmbeddingClientFactory embeddingClientFactory,
                                           @Nullable RerankClientFactory rerankClientFactory) {
        this.modelServiceRepository = modelServiceRepository;
        this.providerRegistry = providerRegistry;
        this.generationClientFactory = generationClientFactory;
        this.embeddingClientFactory = embeddingClientFactory;
        this.rerankClientFactory = rerankClientFactory;
    }

    /**
     * 启动时全量同步已启用模型服务。
     */
    public void registerAllEnabled() {
        List<ModelServiceEntity> services = modelServiceRepository.findAll().stream()
                .filter(ModelServiceEntity::enabled)
                .filter(this::supportsProviderRegistry)
                .toList();

        Set<String> activeServiceIds = new HashSet<>();
        for (ModelServiceEntity service : services) {
            activeServiceIds.add(service.id());
            registerService(service);
        }

        for (String registeredId : providerRegistry.registeredIds()) {
            if (!activeServiceIds.contains(registeredId)) {
                providerRegistry.deregister(registeredId);
            }
        }

        log.info("模型服务运行时注册完成: activeServices={}", activeServiceIds.size());
    }

    /**
     * 同步单个模型服务到运行时。
     *
     * @param service 模型服务
     */
    public void registerService(ModelServiceEntity service) {
        if (!supportsProviderRegistry(service) || !service.enabled()) {
            deregisterService(service.id());
            return;
        }

        evictClientCaches(service.id());

        if (providerRegistry.getConfig(service.id()).isPresent()) {
            providerRegistry.deregister(service.id());
        }
        providerRegistry.register(toProviderConfig(service));
        log.info("模型服务已注册到运行时: id={}, kind={}", service.id(), service.kind());
    }

    /**
     * 从运行时注销模型服务。
     *
     * @param serviceId 服务 ID
     */
    public void deregisterService(String serviceId) {
        evictClientCaches(serviceId);
        if (providerRegistry.getConfig(serviceId).isPresent()) {
            providerRegistry.deregister(serviceId);
            log.info("模型服务已从运行时注销: id={}", serviceId);
        }
    }

    private boolean supportsProviderRegistry(ModelServiceEntity service) {
        return service.kind() == ModelServiceKind.GENERATION || service.kind() == ModelServiceKind.EMBEDDING;
    }

    private ProviderConfig toProviderConfig(ModelServiceEntity service) {
        return switch (service.kind()) {
            case GENERATION -> ModelServiceProviderConfigMapper.toGenerationProviderConfig(service);
            case EMBEDDING -> ModelServiceProviderConfigMapper.toEmbeddingProviderConfig(service);
            case RERANK -> throw new IllegalArgumentException("RERANK 服务不应注册到 ProviderRegistry");
        };
    }

    /**
     * 检查指定模型服务的健康状态。
     *
     * <p>GENERATION/EMBEDDING 走 ProviderRegistry 健康检查；
     * RERANK 通过 RerankClientFactory 发一次轻量 rerank 请求验证连通性。</p>
     *
     * @param serviceId 模型服务 ID
     * @return true 表示健康
     */
    public boolean healthCheck(String serviceId) {
        var service = modelServiceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("模型服务不存在: id=" + serviceId));

        if (service.kind() == ModelServiceKind.RERANK) {
            return healthCheckRerank(service);
        }
        // GENERATION / EMBEDDING 走 ProviderRegistry
        return providerRegistry.healthCheck(serviceId);
    }

    /** RERANK 健康检查 — 用极简 query/document 发一次真实请求。 */
    private boolean healthCheckRerank(ModelServiceEntity service) {
        if (rerankClientFactory == null) {
            log.warn("精排客户端工厂未配置，无法检查: id={}", service.id());
            return false;
        }
        try {
            var client = rerankClientFactory.getOrCreate(service);
            var results = client.rerank("health check", List.of("test document"), 1, null);
            return results != null;
        } catch (Exception e) {
            log.debug("精排服务健康检查失败: id={}, error={}", service.id(), e.getMessage());
            return false;
        }
    }

    /**
     * 驱逐所有客户端工厂中该服务的缓存实例，确保下次调用时基于最新配置重建。
     */
    private void evictClientCaches(String serviceId) {
        if (generationClientFactory != null) {
            generationClientFactory.evict(serviceId);
        }
        if (embeddingClientFactory != null) {
            embeddingClientFactory.evict(serviceId);
        }
        if (rerankClientFactory != null) {
            rerankClientFactory.evict(serviceId);
        }
        log.debug("已清除客户端工厂缓存: serviceId={}", serviceId);
    }
}
