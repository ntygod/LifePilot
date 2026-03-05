package com.lifepilot.llm.registry;

import com.lifepilot.llm.adapter.ProviderAdapter;
import com.lifepilot.llm.adapter.ProviderAdapterFactory;
import com.lifepilot.llm.adapter.SpringAiProviderAdapter;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 注册表。
 *
 * <p>使用 {@link ConcurrentHashMap} 管理 Provider 配置和适配器实例，
 * 支持按场景、能力查询，委托 {@link ProviderHealthChecker} 执行健康检查。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final ConcurrentHashMap<String, ProviderConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SpringAiProviderAdapter> adapters = new ConcurrentHashMap<>();
    private final ProviderAdapterFactory adapterFactory;
    private final ProviderHealthChecker healthChecker;

    /**
     * 创建 Provider 注册表。
     *
     * @param adapterFactory 适配器工厂
     * @param healthChecker  健康检查器
     */
    public ProviderRegistry(ProviderAdapterFactory adapterFactory,
                            ProviderHealthChecker healthChecker) {
        this.adapterFactory = adapterFactory;
        this.healthChecker = healthChecker;
    }

    /**
     * 注册 Provider。重复 ID 抛出 IllegalArgumentException。
     *
     * @param config Provider 配置
     */
    public void register(ProviderConfig config) {
        if (configs.containsKey(config.id())) {
            throw new IllegalArgumentException("Provider 已注册: id=" + config.id());
        }
        var adapter = adapterFactory.create(config);
        configs.put(config.id(), config);
        adapters.put(config.id(), adapter);
        log.info("Provider 注册成功: id={}, type={}, model={}",
                config.id(), config.type(), config.modelName());
    }

    /**
     * 注销 Provider。
     *
     * @param providerId Provider ID
     */
    public void deregister(String providerId) {
        configs.remove(providerId);
        adapters.remove(providerId);
        log.info("Provider 注销: id={}", providerId);
    }

    /**
     * 按场景查询已启用的 Provider，按 priority 升序排序。
     *
     * @param scene 场景名称
     * @return 匹配的 Provider 配置列表（不可变）
     */
    public List<ProviderConfig> findByScene(String scene) {
        return configs.values().stream()
                .filter(ProviderConfig::enabled)
                .filter(c -> c.supportsScene(scene))
                .sorted(Comparator.comparingInt(ProviderConfig::priority))
                .toList();
    }

    /**
     * 按能力查询已启用的 Provider，按 priority 升序排序。
     *
     * @param capability 能力枚举
     * @return 匹配的 Provider 配置列表（不可变）
     */
    public List<ProviderConfig> findByCapability(ProviderCapability capability) {
        return configs.values().stream()
                .filter(ProviderConfig::enabled)
                .filter(c -> c.hasCapability(capability))
                .sorted(Comparator.comparingInt(ProviderConfig::priority))
                .toList();
    }

    /**
     * 获取 Provider 适配器。
     *
     * @param providerId Provider ID
     * @return 适配器实例
     * @throws IllegalArgumentException 若 Provider 未注册
     */
    public ProviderAdapter getAdapter(String providerId) {
        var adapter = adapters.get(providerId);
        if (adapter == null) {
            throw new IllegalArgumentException("Provider 未注册: id=" + providerId);
        }
        return adapter;
    }

    /**
     * 获取 Provider 配置。
     *
     * @param providerId Provider ID
     * @return 配置，未注册时返回 empty
     */
    public Optional<ProviderConfig> getConfig(String providerId) {
        return Optional.ofNullable(configs.get(providerId));
    }

    /**
     * 对所有已注册 Provider 执行健康检查。
     *
     * @return 不可变的健康状态映射
     */
    public Map<String, Boolean> healthCheckAll() {
        return healthChecker.checkAll(Map.copyOf(adapters));
    }

    public boolean healthCheck(String providerId) {
        var adapter = adapters.get(providerId);
        if (adapter == null) {
            throw new IllegalArgumentException("Provider 未注册: id=" + providerId);
        }
        return adapter.healthCheck();
    }

    /**
     * 获取所有已注册 Provider ID。
     *
     * @return 不可变的 ID 集合
     */
    public Set<String> registeredIds() {
        return Set.copyOf(configs.keySet());
    }
}
