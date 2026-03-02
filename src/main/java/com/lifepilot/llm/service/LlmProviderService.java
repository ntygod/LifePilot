package com.lifepilot.llm.service;

import com.lifepilot.llm.config.LlmProviderEntity;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.repository.LlmProviderRepository;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LLM Provider 服务层。
 *
 * <p>提供 Provider 配置的 CRUD 操作，以及注册到 ProviderRegistry 的功能。
 *
 * @author zsg
 * @since 2026-02-27
 */
@Service
public class LlmProviderService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);

    private final LlmProviderRepository repository;
    private final ProviderRegistry providerRegistry;

    public LlmProviderService(LlmProviderRepository repository,
                              @Nullable ProviderRegistry providerRegistry) {
        this.repository = repository;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 获取所有 Provider。
     *
     * @return Provider 列表
     */
    public List<LlmProviderEntity> findAll() {
        return repository.findAll();
    }

    /**
     * 获取所有已启用的 Provider。
     *
     * @return 已启用的 Provider 列表
     */
    public List<LlmProviderEntity> findAllEnabled() {
        return repository.findAllEnabled();
    }

    /**
     * 根据 ID 获取 Provider。
     *
     * @param id Provider ID
     * @return Provider Optional
     */
    public java.util.Optional<LlmProviderEntity> findById(String id) {
        return repository.findById(id);
    }

    /**
     * 获取所有预设置的 Provider。
     *
     * @return 预设置 Provider 列表
     */
    public List<LlmProviderEntity> findPresets() {
        return repository.findPresets();
    }

    /**
     * 创建或更新 Provider。
     *
     * @param entity Provider 实体
     * @return 保存后的实体
     */
    @Transactional
    public LlmProviderEntity save(LlmProviderEntity entity) {
        repository.save(entity);
        // 如果已启用，注册到 ProviderRegistry
        if (entity.enabled() && providerRegistry != null) {
            try {
                ProviderConfig config = entity.toProviderConfig();
                // 如果已注册，先注销
                if (providerRegistry.getConfig(config.id()).isPresent()) {
                    providerRegistry.deregister(config.id());
                }
                providerRegistry.register(config);
                log.info("Provider 已注册: id={}", entity.id());
            } catch (Exception e) {
                log.warn("Provider 注册失败: id={}, error={}", entity.id(), e.getMessage());
            }
        } else if (!entity.enabled() && providerRegistry != null) {
            // 如果禁用，从注册表注销
            try {
                providerRegistry.deregister(entity.id());
                log.info("Provider 已注销: id={}", entity.id());
            } catch (Exception e) {
                log.debug("Provider 注销失败（可能未注册）: id={}", entity.id());
            }
        }
        return entity;
    }

    /**
     * 删除 Provider。
     *
     * <p>预设 Provider 仅用于方便用户初始配置，用户可以自由删除、启用或禁用任何 Provider。
     *
     * @param id Provider ID
     * @return 删除成功返回 true
     */
    @Transactional
    public boolean deleteById(String id) {
        java.util.Optional<LlmProviderEntity> entity = repository.findById(id);
        if (entity.isEmpty()) {
            return false;
        }
        int rows = repository.deleteById(id);
        if (rows > 0 && providerRegistry != null) {
            try {
                providerRegistry.deregister(id);
                log.info("Provider 已注销: id={}", id);
            } catch (Exception e) {
                log.debug("Provider 注销失败（可能未注册）: id={}", id);
            }
        }
        return rows > 0;
    }

    /**
     * 注册所有已启用的 Provider 到 ProviderRegistry。
     *
     * <p>用于启动时初始化。
     */
    public void registerAllEnabled() {
        if (providerRegistry == null) {
            log.warn("ProviderRegistry 不可用，跳过 Provider 注册");
            return;
        }
        List<LlmProviderEntity> enabled = repository.findAllEnabled();
        int registered = 0;
        for (LlmProviderEntity entity : enabled) {
            try {
                ProviderConfig config = entity.toProviderConfig();
                // 如果已注册，先注销
                if (providerRegistry.getConfig(config.id()).isPresent()) {
                    providerRegistry.deregister(config.id());
                }
                providerRegistry.register(config);
                registered++;
            } catch (Exception e) {
                log.warn("Provider 注册失败: id={}, error={}", entity.id(), e.getMessage());
            }
        }
        log.info("Provider 注册完成: 成功={}, 总数={}", registered, enabled.size());
    }

    /**
     * 从 ProviderConfig 创建 LlmProviderEntity（用于从配置文件迁移）。
     *
     * @param config ProviderConfig
     * @return LlmProviderEntity
     */
    public LlmProviderEntity fromProviderConfig(ProviderConfig config) {
        return new LlmProviderEntity(
                config.id(),
                config.type(),
                config.apiUrl(),
                config.apiKey(),
                config.modelName(),
                config.timeoutSeconds(),
                config.priority(),
                config.scenes(),
                config.capabilities(),
                config.enabled(),
                config.costPerInputToken(),
                config.costPerOutputToken(),
                config.maxContextWindow(),
                config.embeddingDimension(),
                config.supportsStreaming(),
                false, // 非预设置
                null, // displayName
                null  // description
        );
    }
}
