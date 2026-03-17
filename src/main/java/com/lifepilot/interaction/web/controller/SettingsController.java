package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.RerankerSettingsRequest;
import com.lifepilot.interaction.web.model.RerankerSettingsResponse;
import com.lifepilot.interaction.web.model.UserSettings;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** JSON 序列化/反序列化 */
    private final ObjectMapper objectMapper;

    /** 知识库配置（用于读取全局 Reranker 默认值） */
    private final KnowledgeBaseProperties knowledgeBaseProperties;

    /** 记忆配置（用于读取记忆精排默认值） */
    private final MemoryProperties memoryProperties;

    /**
     * 创建 SettingsController。
     *
     * @param settingsRepository      用户设置仓储
     * @param providerRegistry        Provider 注册表（可选，可为 null）
     * @param objectMapper            JSON 序列化器
     * @param knowledgeBaseProperties 知识库配置（可选，可为 null）
     * @param memoryProperties        记忆配置（可选，可为 null）
     */
    public SettingsController(UserSettingsRepository settingsRepository,
                              @Autowired(required = false) ProviderRegistry providerRegistry,
                              ObjectMapper objectMapper,
                              @Autowired(required = false) @Nullable KnowledgeBaseProperties knowledgeBaseProperties,
                              @Autowired(required = false) @Nullable MemoryProperties memoryProperties) {
        this.settingsRepository = settingsRepository;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
        this.knowledgeBaseProperties = knowledgeBaseProperties;
        this.memoryProperties = memoryProperties;
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

        log.debug("更新用户设置: theme={}, language={}, llmProvider={}, sceneProviders={}",
                settings.theme(), settings.language(), settings.llmProvider(), settings.sceneProviders());
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
                    Map<String, Object> provider = new HashMap<>();
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
                    Map<String, Object> provider = new HashMap<>();
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

    // ==================== Reranker 配置端点 ====================

    /**
     * 获取全局 Reranker 配置。
     *
     * <p>优先从数据库读取持久化配置，若为空则从 {@link KnowledgeBaseProperties.Reranker}
     * 和 {@link MemoryProperties.Reranker} 读取默认值。apiKey 返回掩码值。
     *
     * @return Reranker 配置响应
     */
    @GetMapping("/reranker")
    public ResponseEntity<RerankerSettingsResponse> getRerankerSettings() {
        log.debug("获取 Reranker 配置");
        String json = settingsRepository.getRerankerConfig();
        Map<String, Object> config = deserializeRerankerConfig(json);

        // 从 KnowledgeBaseProperties.Reranker 读取默认值
        var kbReranker = knowledgeBaseProperties != null
                ? knowledgeBaseProperties.reranker()
                : new KnowledgeBaseProperties.Reranker(false, null, null, 0, null, 0, null, null, null, 0);
        // 从 MemoryProperties.Reranker 读取默认值
        var memReranker = memoryProperties != null
                ? memoryProperties.getReranker()
                : new MemoryProperties.Reranker();

        // DB 值覆盖默认值
        boolean enabled = getConfigValue(config, "enabled", Boolean.class, kbReranker.enabled());
        String type = getConfigValue(config, "type", String.class, kbReranker.type());
        String model = getConfigValue(config, "model", String.class, kbReranker.model());
        int topK = getConfigValue(config, "topK", Integer.class, kbReranker.topK());
        String llmMode = getConfigValue(config, "llmMode", String.class, kbReranker.llmMode());
        String apiProvider = getConfigValue(config, "apiProvider", String.class, kbReranker.apiProvider());
        String apiKey = getConfigValue(config, "apiKey", String.class, kbReranker.apiKey());
        String apiEndpoint = getConfigValue(config, "apiEndpoint", String.class, kbReranker.apiEndpoint());
        int apiTimeoutMs = getConfigValue(config, "apiTimeoutMs", Integer.class, kbReranker.apiTimeoutMs());
        boolean memoryRerankEnabled = getConfigValue(config, "memoryRerankEnabled", Boolean.class, memReranker.isEnabled());
        int memoryRerankTopK = getConfigValue(config, "memoryRerankTopK", Integer.class, memReranker.getTopK());

        var response = new RerankerSettingsResponse(
                enabled, type, model, topK, llmMode,
                apiProvider, maskApiKey(apiKey), apiEndpoint, apiTimeoutMs,
                memoryRerankEnabled, memoryRerankTopK);
        return ResponseEntity.ok(response);
    }

    /**
     * 更新全局 Reranker 配置。
     *
     * <p>将请求中非 null 的字段合并到现有配置中，持久化到数据库。
     * 当 apiKey 为掩码值（以 {@code ****} 开头）时保留原值。
     *
     * @param request Reranker 配置更新请求
     * @return 更新后的 Reranker 配置响应
     */
    @PutMapping("/reranker")
    public ResponseEntity<RerankerSettingsResponse> updateRerankerSettings(
            @RequestBody RerankerSettingsRequest request) {
        log.info("更新 Reranker 配置: type={}, enabled={}", request.type(), request.enabled());

        // 读取现有配置
        String existingJson = settingsRepository.getRerankerConfig();
        Map<String, Object> config = deserializeRerankerConfig(existingJson);

        // 从 KnowledgeBaseProperties.Reranker 读取默认值（用于首次写入时的基线）
        var kbReranker = knowledgeBaseProperties != null
                ? knowledgeBaseProperties.reranker()
                : new KnowledgeBaseProperties.Reranker(false, null, null, 0, null, 0, null, null, null, 0);
        var memReranker = memoryProperties != null
                ? memoryProperties.getReranker()
                : new MemoryProperties.Reranker();

        // 合并请求字段（null 字段保留原值）
        if (request.enabled() != null) config.put("enabled", request.enabled());
        if (request.type() != null) config.put("type", request.type());
        if (request.model() != null) config.put("model", request.model());
        if (request.topK() != null) config.put("topK", request.topK());
        if (request.llmMode() != null) config.put("llmMode", request.llmMode());
        if (request.apiProvider() != null) config.put("apiProvider", request.apiProvider());
        if (request.apiEndpoint() != null) config.put("apiEndpoint", request.apiEndpoint());
        if (request.apiTimeoutMs() != null) config.put("apiTimeoutMs", request.apiTimeoutMs());
        if (request.memoryRerankEnabled() != null) config.put("memoryRerankEnabled", request.memoryRerankEnabled());
        if (request.memoryRerankTopK() != null) config.put("memoryRerankTopK", request.memoryRerankTopK());

        // apiKey 特殊处理：掩码值时保留原值
        if (request.apiKey() != null && !isApiKeyMasked(request.apiKey())) {
            config.put("apiKey", request.apiKey());
        }

        // 序列化并持久化
        try {
            String updatedJson = objectMapper.writeValueAsString(config);
            settingsRepository.saveRerankerConfig(updatedJson);
        } catch (Exception e) {
            log.error("Reranker 配置序列化失败: error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        // 构建响应（从合并后的 config 读取，apiKey 掩码）
        boolean enabled = getConfigValue(config, "enabled", Boolean.class, kbReranker.enabled());
        String type = getConfigValue(config, "type", String.class, kbReranker.type());
        String model = getConfigValue(config, "model", String.class, kbReranker.model());
        int topK = getConfigValue(config, "topK", Integer.class, kbReranker.topK());
        String llmMode = getConfigValue(config, "llmMode", String.class, kbReranker.llmMode());
        String apiProvider = getConfigValue(config, "apiProvider", String.class, kbReranker.apiProvider());
        String apiKey = getConfigValue(config, "apiKey", String.class, kbReranker.apiKey());
        String apiEndpoint = getConfigValue(config, "apiEndpoint", String.class, kbReranker.apiEndpoint());
        int apiTimeoutMs = getConfigValue(config, "apiTimeoutMs", Integer.class, kbReranker.apiTimeoutMs());
        boolean memoryRerankEnabled = getConfigValue(config, "memoryRerankEnabled", Boolean.class, memReranker.isEnabled());
        int memoryRerankTopK = getConfigValue(config, "memoryRerankTopK", Integer.class, memReranker.getTopK());

        var response = new RerankerSettingsResponse(
                enabled, type, model, topK, llmMode,
                apiProvider, maskApiKey(apiKey), apiEndpoint, apiTimeoutMs,
                memoryRerankEnabled, memoryRerankTopK);
        return ResponseEntity.ok(response);
    }

    // ==================== Reranker 辅助方法 ====================

    // ==================== 知识库全局配置端点 ====================

    /**
     * 获取知识库全局配置。
     *
     * <p>优先从数据库读取持久化配置，若为空则从 {@link KnowledgeBaseProperties} 读取默认值。</p>
     *
     * @return 知识库配置响应
     */
    @GetMapping("/knowledge")
    public ResponseEntity<Map<String, Object>> getKnowledgeSettings() {
        log.debug("获取知识库全局配置");
        String json = settingsRepository.getKnowledgeConfig();
        Map<String, Object> config = deserializeRerankerConfig(json);

        var props = knowledgeBaseProperties != null
                ? knowledgeBaseProperties
                : new KnowledgeBaseProperties(null, 0, true, null, null, null, null, null, null, null);

        var chunking = props.chunking();
        var retrieval = props.retrieval();
        var vectorIndexer = props.vectorIndexer();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", getConfigValue(config, "enabled", Boolean.class, props.enabled()));
        result.put("maxFileSize", getConfigValue(config, "maxFileSize", Long.class, props.maxFileSize()));

        // 分块配置
        Map<String, Object> chunkingConfig = new LinkedHashMap<>();
        chunkingConfig.put("defaultStrategy", getConfigValue(config, "chunkingStrategy", String.class, chunking.defaultStrategy()));
        chunkingConfig.put("chunkSize", getConfigValue(config, "chunkSize", Integer.class, chunking.fixedSize().chunkSize()));
        chunkingConfig.put("overlapSize", getConfigValue(config, "overlapSize", Integer.class, chunking.fixedSize().overlapSize()));
        chunkingConfig.put("maxChunkTokens", getConfigValue(config, "maxChunkTokens", Integer.class, chunking.fixedSize().maxChunkTokens()));
        result.put("chunking", chunkingConfig);

        // 检索配置
        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        retrievalConfig.put("defaultTopK", getConfigValue(config, "retrievalTopK", Integer.class, retrieval.defaultTopK()));
        retrievalConfig.put("vectorWeight", getConfigValue(config, "vectorWeight", Double.class, retrieval.vectorWeight()));
        retrievalConfig.put("ftsWeight", getConfigValue(config, "ftsWeight", Double.class, retrieval.ftsWeight()));
        retrievalConfig.put("minRelevanceScore", getConfigValue(config, "minRelevanceScore", Double.class, retrieval.minRelevanceScore()));
        result.put("retrieval", retrievalConfig);

        // 向量索引配置
        Map<String, Object> vectorConfig = new LinkedHashMap<>();
        vectorConfig.put("embeddingDimension", getConfigValue(config, "embeddingDimension", Integer.class, vectorIndexer.embeddingDimension()));
        vectorConfig.put("batchSize", getConfigValue(config, "vectorBatchSize", Integer.class, vectorIndexer.batchSize()));
        result.put("vectorIndexer", vectorConfig);

        return ResponseEntity.ok(result);
    }

    /**
     * 更新知识库全局配置。
     *
     * @param request 知识库配置更新请求
     * @return 更新后的知识库配置
     */
    @PutMapping("/knowledge")
    public ResponseEntity<Map<String, Object>> updateKnowledgeSettings(
            @RequestBody Map<String, Object> request) {
        log.info("更新知识库全局配置");

        String existingJson = settingsRepository.getKnowledgeConfig();
        Map<String, Object> config = deserializeRerankerConfig(existingJson);

        // 合并顶层字段
        for (String key : List.of("enabled", "maxFileSize", "chunkingStrategy",
                "chunkSize", "overlapSize", "maxChunkTokens",
                "retrievalTopK", "vectorWeight", "ftsWeight", "minRelevanceScore",
                "embeddingDimension", "vectorBatchSize")) {
            if (request.containsKey(key)) {
                config.put(key, request.get(key));
            }
        }

        try {
            String updatedJson = objectMapper.writeValueAsString(config);
            settingsRepository.saveKnowledgeConfig(updatedJson);
        } catch (Exception e) {
            log.error("知识库配置序列化失败: error={}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        // 返回完整配置
        return getKnowledgeSettings();
    }

    /**
     * 对 apiKey 进行掩码处理，仅保留末尾 4 位。
     *
     * @param apiKey 原始 apiKey
     * @return 掩码值（如 {@code ****abcd}），null 或长度不足 4 时返回空字符串
     */
    private String maskApiKey(@Nullable String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****" + apiKey;
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 判断 apiKey 是否为掩码值（以 {@code ****} 开头）。
     *
     * @param apiKey apiKey 值
     * @return 是否为掩码值
     */
    private boolean isApiKeyMasked(@Nullable String apiKey) {
        return apiKey != null && apiKey.startsWith("****");
    }

    /**
     * 反序列化 Reranker 配置 JSON 为 Map。
     *
     * @param json JSON 字符串
     * @return 可变 Map，解析失败时返回空 Map
     */
    private Map<String, Object> deserializeRerankerConfig(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? new HashMap<>(result) : new HashMap<>();
        } catch (Exception e) {
            log.warn("Reranker 配置反序列化失败，使用空配置: error={}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 从配置 Map 中读取指定类型的值，不存在时返回默认值。
     *
     * @param config       配置 Map
     * @param key          配置键
     * @param type         目标类型
     * @param defaultValue 默认值
     * @param <T>          值类型
     * @return 配置值或默认值
     */
    @SuppressWarnings("unchecked")
    private <T> T getConfigValue(Map<String, Object> config, String key, Class<T> type, T defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (type == Integer.class && value instanceof Number number) {
            return (T) Integer.valueOf(number.intValue());
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return defaultValue;
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
            case TEI -> "TEI (" + config.modelName() + ")";
            case WENXIN -> "文心一言";
            case GLM -> "智谱 GLM";
            case OPENAI_COMPATIBLE -> "OpenAI 兼容";
            case ANTHROPIC -> "Anthropic Claude";
        };
    }
}
