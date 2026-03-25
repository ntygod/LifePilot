package com.lifepilot.modelservice.service;

import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.support.ModelServiceProviderConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    public ModelServiceRegistrationService(ModelServiceRepository modelServiceRepository,
                                           ProviderRegistry providerRegistry) {
        this.modelServiceRepository = modelServiceRepository;
        this.providerRegistry = providerRegistry;
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
}
