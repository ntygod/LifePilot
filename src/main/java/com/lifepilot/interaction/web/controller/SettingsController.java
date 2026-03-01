package com.lifepilot.interaction.web.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.lifepilot.interaction.web.model.UserSettings;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户设置 REST 端点，提供设置的读取和更新功能。
 *
 * <p>使用数据库持久化存储设置，通过 {@link UserSettingsRepository} 访问。
 *
 * @author zsg
 * @since 2026-02-26
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    /** 用户设置仓储 */
    private final UserSettingsRepository settingsRepository;

    /** Provider 注册表（可选，用于获取可用 Provider 列表） */
    private final ProviderRegistry providerRegistry;

    /**
     * 创建 SettingsController。
     *
     * @param settingsRepository 用户设置仓储
     * @param providerRegistry    Provider 注册表（可选，可为 null）
     */
    public SettingsController(UserSettingsRepository settingsRepository,
                              @Autowired(required = false) ProviderRegistry providerRegistry) {
        this.settingsRepository = settingsRepository;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 获取当前用户设置。
     *
     * @return 当前用户设置
     */
    @GetMapping
    public ResponseEntity<UserSettings> getSettings() {
        log.debug("获取用户设置");
        UserSettings settings = settingsRepository.getSettings();
        return ResponseEntity.ok(settings);
    }

    /**
     * 更新用户设置。
     *
     * @param settings 新的用户设置
     * @return 更新后的用户设置
     * @throws IllegalArgumentException 如果设置参数无效
     */
    @PutMapping
    public ResponseEntity<UserSettings> updateSettings(@RequestBody UserSettings settings) {
        if (settings.theme() == null || settings.theme().isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        if (settings.language() == null || settings.language().isBlank()) {
            throw new IllegalArgumentException("语言不能为空");
        }
        if (settings.llmProvider() == null || settings.llmProvider().isBlank()) {
            throw new IllegalArgumentException("LLM Provider 不能为空");
        }

        log.debug("更新用户设置: theme={}, language={}, llmProvider={}",
                settings.theme(), settings.language(), settings.llmProvider());
        settingsRepository.save(settings);
        UserSettings savedSettings = settingsRepository.getSettings();
        return ResponseEntity.ok(savedSettings);
    }

    /**
     * 获取可用的 LLM Provider 列表（仅支持 CHAT 能力的 Provider）。
     *
     * @return Provider 列表，包含详细信息（id、type、modelName、displayName、capabilities、priority、cost、scenes 等）
     */
    @GetMapping("/providers")
    public ResponseEntity<List<Map<String, Object>>> getProviders() {
        log.debug("获取可用 LLM Provider 列表");
        if (providerRegistry == null) {
            log.warn("ProviderRegistry 不可用，返回空列表");
            return ResponseEntity.ok(List.of());
        }
        List<ProviderConfig> chatProviders = providerRegistry.findByCapability(ProviderCapability.CHAT);
        List<Map<String, Object>> providers = chatProviders.stream()
                .map(config -> {
                    Map<String, Object> provider = new java.util.HashMap<>();
                    provider.put("id", config.id());
                    provider.put("type", config.type().name());
                    provider.put("modelName", config.modelName());
                    provider.put("displayName", generateDisplayName(config));
                    provider.put("capabilities", config.capabilities().stream()
                            .map(Enum::name)
                            .collect(Collectors.toList()));
                    provider.put("priority", config.priority());
                    provider.put("costPerInputToken", config.costPerInputToken());
                    provider.put("costPerOutputToken", config.costPerOutputToken());
                    provider.put("scenes", config.scenes());
                    provider.put("maxContextWindow", config.maxContextWindow());
                    provider.put("supportsStreaming", config.supportsStreaming());
                    provider.put("enabled", config.enabled());
                    return provider;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(providers);
    }

    /**
     * 获取所有 Provider 的健康状态。
     *
     * @return Provider ID 到健康状态的映射（true=健康，false=不健康）
     */
    @GetMapping("/providers/health")
    public ResponseEntity<Map<String, Boolean>> getProviderHealth() {
        log.debug("获取 Provider 健康状态");
        if (providerRegistry == null) {
            log.warn("ProviderRegistry 不可用，返回空映射");
            return ResponseEntity.ok(Map.of());
        }
        Map<String, Boolean> healthStatus = providerRegistry.healthCheckAll();
        return ResponseEntity.ok(healthStatus);
    }

    /**
     * 获取指定 Provider 的详细信息。
     *
     * @param providerId Provider ID
     * @return Provider 详细信息
     */
    @GetMapping("/providers/{providerId}")
    public ResponseEntity<Map<String, Object>> getProviderDetail(@PathVariable String providerId) {
        log.debug("获取 Provider 详细信息: id={}", providerId);
        if (providerRegistry == null) {
            log.warn("ProviderRegistry 不可用，返回 404");
            return ResponseEntity.notFound().build();
        }
        return providerRegistry.getConfig(providerId)
                .map(config -> {
                    Map<String, Object> provider = new java.util.HashMap<>();
                    provider.put("id", config.id());
                    provider.put("type", config.type().name());
                    provider.put("modelName", config.modelName());
                    provider.put("displayName", generateDisplayName(config));
                    provider.put("capabilities", config.capabilities().stream()
                            .map(Enum::name)
                            .collect(Collectors.toList()));
                    provider.put("priority", config.priority());
                    provider.put("costPerInputToken", config.costPerInputToken());
                    provider.put("costPerOutputToken", config.costPerOutputToken());
                    provider.put("scenes", config.scenes());
                    provider.put("maxContextWindow", config.maxContextWindow());
                    provider.put("supportsStreaming", config.supportsStreaming());
                    provider.put("enabled", config.enabled());
                    provider.put("apiUrl", config.apiUrl());
                    provider.put("timeoutSeconds", config.timeoutSeconds());
                    // 注意：健康状态通过 /api/settings/providers/health 接口单独获取，避免阻塞
                    return ResponseEntity.ok(provider);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 生成 Provider 显示名称。
     *
     * @param config Provider 配置
     * @return 显示名称
     */
    private String generateDisplayName(ProviderConfig config) {
        return switch (config.type()) {
            case OLLAMA -> "Ollama (" + config.modelName() + ")";
            case DEEPSEEK -> "DeepSeek";
            case QWEN -> "通义千问";
            case WENXIN -> "文心一言";
            case GLM -> "智谱 GLM";
            case OPENAI_COMPATIBLE -> "OpenAI 兼容";
        };
    }
}
