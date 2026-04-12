package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.SearchSettingsRequest;
import com.lifepilot.interaction.web.model.SearchSettingsResponse;
import com.lifepilot.interaction.web.model.UserSettings;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.meta.config.MetaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户通用设置接口。
 *
 * <p>这里只负责基础用户偏好、知识库附属设置、联网搜索设置和渠道设置。
 * 模型服务、生成路由、向量路由、精排路由均由独立接口维护。</p>
 *
 * @author zsg
 * @since 2026-03-24
 */
@RestController
@RequestMapping("/api/settings")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private static final java.util.Set<String> SENSITIVE_CHANNEL_FIELDS = java.util.Set.of(
            "appSecret", "secret", "verificationToken", "encryptKey", "encodingAesKey", "token"
    );

    private final UserSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;
    private final KnowledgeBaseProperties knowledgeBaseProperties;
    private final MetaProperties metaProperties;
    private final WorkspaceResolver workspaceResolver;
    private final String dataDir;

    public SettingsController(UserSettingsRepository settingsRepository,
                              ObjectMapper objectMapper,
                              @Nullable KnowledgeBaseProperties knowledgeBaseProperties,
                              @Nullable MetaProperties metaProperties,
                              WorkspaceResolver workspaceResolver,
                              @org.springframework.beans.factory.annotation.Value("${zhiwei.data-dir}") String dataDir) {
        this.settingsRepository = settingsRepository;
        this.objectMapper = objectMapper;
        this.knowledgeBaseProperties = knowledgeBaseProperties;
        this.metaProperties = metaProperties;
        this.workspaceResolver = workspaceResolver;
        this.dataDir = dataDir;
    }

    @GetMapping
    public ApiResponse<UserSettings> getSettings() {
        return ApiResponse.ok(settingsRepository.getSettings());
    }

    @PutMapping
    public ApiResponse<UserSettings> updateSettings(@RequestBody UserSettings settings) {
        if (settings.theme() == null || settings.theme().isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        if (settings.language() == null || settings.language().isBlank()) {
            throw new IllegalArgumentException("语言不能为空");
        }
        settingsRepository.save(settings);
        log.debug("用户设置已更新: theme={}, language={}", settings.theme(), settings.language());
        return ApiResponse.ok(settingsRepository.getSettings());
    }

    @GetMapping("/knowledge")
    public ApiResponse<Map<String, Object>> getKnowledgeSettings() {
        log.debug("获取知识库设置");
        Map<String, Object> config = deserializeJsonConfig(settingsRepository.getKnowledgeConfig());
        KnowledgeBaseProperties properties = knowledgeBaseProperties != null
                ? knowledgeBaseProperties
                : new KnowledgeBaseProperties(null, 0, true, null, null, null, null, null, null, null, null);

        var chunking = properties.chunking();
        var retrieval = properties.retrieval();
        var vectorIndexer = properties.vectorIndexer();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", getConfigValue(config, "enabled", Boolean.class, properties.enabled()));
        result.put("maxFileSize", getConfigValue(config, "maxFileSize", Long.class, properties.maxFileSize()));

        Map<String, Object> chunkingConfig = new LinkedHashMap<>();
        chunkingConfig.put("defaultStrategy",
                getConfigValue(config, "chunkingStrategy", String.class, chunking.defaultStrategy()));
        chunkingConfig.put("chunkSize",
                getConfigValue(config, "chunkSize", Integer.class, chunking.fixedSize().chunkSize()));
        chunkingConfig.put("overlapSize",
                getConfigValue(config, "overlapSize", Integer.class, chunking.fixedSize().overlapSize()));
        chunkingConfig.put("maxChunkTokens",
                getConfigValue(config, "maxChunkTokens", Integer.class, chunking.fixedSize().maxChunkTokens()));
        result.put("chunking", chunkingConfig);

        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        retrievalConfig.put("defaultTopK",
                getConfigValue(config, "retrievalTopK", Integer.class, retrieval.defaultTopK()));
        retrievalConfig.put("vectorWeight",
                getConfigValue(config, "vectorWeight", Double.class, retrieval.vectorWeight()));
        retrievalConfig.put("ftsWeight",
                getConfigValue(config, "ftsWeight", Double.class, retrieval.ftsWeight()));
        retrievalConfig.put("minRelevanceScore",
                getConfigValue(config, "minRelevanceScore", Double.class, retrieval.minRelevanceScore()));
        result.put("retrieval", retrievalConfig);

        Map<String, Object> vectorConfig = new LinkedHashMap<>();
        vectorConfig.put("embeddingDimension",
                getConfigValue(config, "embeddingDimension", Integer.class, vectorIndexer.embeddingDimension()));
        vectorConfig.put("batchSize",
                getConfigValue(config, "vectorBatchSize", Integer.class, vectorIndexer.batchSize()));
        result.put("vectorIndexer", vectorConfig);

        return ApiResponse.ok(result);
    }

    @PutMapping("/knowledge")
    public ApiResponse<Map<String, Object>> updateKnowledgeSettings(@RequestBody Map<String, Object> request) {
        log.info("更新知识库设置");
        Map<String, Object> config = deserializeJsonConfig(settingsRepository.getKnowledgeConfig());
        for (String key : List.of(
                "enabled",
                "maxFileSize",
                "chunkingStrategy",
                "chunkSize",
                "overlapSize",
                "maxChunkTokens",
                "retrievalTopK",
                "vectorWeight",
                "ftsWeight",
                "minRelevanceScore",
                "embeddingDimension",
                "vectorBatchSize")) {
            if (request.containsKey(key)) {
                config.put(key, request.get(key));
            }
        }
        settingsRepository.saveKnowledgeConfig(writeJson(config));
        return getKnowledgeSettings();
    }

    @GetMapping("/search")
    public ApiResponse<SearchSettingsResponse> getSearchSettings() {
        log.debug("获取联网搜索设置");
        Map<String, Object> config = deserializeJsonConfig(settingsRepository.getSearchConfig());
        MetaProperties.Infra.WebSearch defaults = metaProperties != null
                ? metaProperties.getInfra().getWebSearch()
                : new MetaProperties.Infra.WebSearch();

        String provider = normalizeSearchProvider(getConfigValue(config, "provider", String.class, defaults.getProvider()));
        String apiKey = getConfigValue(config, "apiKey", String.class, defaults.getApiKey());
        int maxResults = clamp(getConfigValue(config, "maxResults", Integer.class, defaults.getMaxResults()), 1, 20);
        String searchDepth = normalizeSearchDepth(
                getConfigValue(config, "searchDepth", String.class, defaults.getSearchDepth()));
        String topic = normalizeSearchTopic(getConfigValue(config, "topic", String.class, defaults.getTopic()));
        boolean includeAnswer = getConfigValue(config, "includeAnswer", Boolean.class, defaults.isIncludeAnswer());
        int connectTimeoutSeconds = Math.max(1,
                getConfigValue(config, "connectTimeoutSeconds", Integer.class, defaults.getConnectTimeoutSeconds()));
        int readTimeoutSeconds = Math.max(1,
                getConfigValue(config, "readTimeoutSeconds", Integer.class, defaults.getReadTimeoutSeconds()));

        return ApiResponse.ok(new SearchSettingsResponse(
                provider,
                maskApiKey(apiKey),
                maxResults,
                searchDepth,
                topic,
                includeAnswer,
                connectTimeoutSeconds,
                readTimeoutSeconds
        ));
    }

    @PutMapping("/search")
    public ApiResponse<SearchSettingsResponse> updateSearchSettings(@RequestBody SearchSettingsRequest request) {
        log.info("更新联网搜索设置: provider={}, topic={}, depth={}",
                request.provider(), request.topic(), request.searchDepth());

        Map<String, Object> config = deserializeJsonConfig(settingsRepository.getSearchConfig());
        if (request.provider() != null) {
            config.put("provider", normalizeSearchProvider(request.provider()));
        } else if (!config.containsKey("provider")) {
            config.put("provider", "tavily");
        }
        if (request.maxResults() != null) {
            config.put("maxResults", clamp(request.maxResults(), 1, 20));
        }
        if (request.searchDepth() != null) {
            config.put("searchDepth", normalizeSearchDepth(request.searchDepth()));
        }
        if (request.topic() != null) {
            config.put("topic", normalizeSearchTopic(request.topic()));
        }
        if (request.includeAnswer() != null) {
            config.put("includeAnswer", request.includeAnswer());
        }
        if (request.connectTimeoutSeconds() != null) {
            config.put("connectTimeoutSeconds", Math.max(1, request.connectTimeoutSeconds()));
        }
        if (request.readTimeoutSeconds() != null) {
            config.put("readTimeoutSeconds", Math.max(1, request.readTimeoutSeconds()));
        }
        if (request.apiKey() != null && !isApiKeyMasked(request.apiKey())) {
            config.put("apiKey", request.apiKey().trim());
        }

        settingsRepository.saveSearchConfig(writeJson(config));
        return getSearchSettings();
    }

    @GetMapping("/data-dir")
    public ApiResponse<Map<String, String>> getDataDir() {
        return ApiResponse.ok(Map.of("dataDir", dataDir));
    }

    @GetMapping("/workspace")
    public ApiResponse<Map<String, String>> getWorkspaceSettings() {
        UserSettings settings = settingsRepository.getSettings();
        Path resolved = workspaceResolver.resolve();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("defaultWorkspace", settings.defaultWorkspace());
        result.put("resolvedPath", resolved.toString());
        result.put("systemDefault", workspaceResolver.getDefaultDir());
        return ApiResponse.ok(result);
    }

    @PutMapping("/workspace")
    public ApiResponse<Map<String, String>> updateWorkspaceSettings(@RequestBody Map<String, String> request) {
        String workspace = request.get("defaultWorkspace");
        // 空字符串视为清除自定义设置
        if (workspace != null && workspace.isBlank()) {
            workspace = null;
        }
        // 校验路径有效性
        if (workspace != null) {
            if (workspace.length() > 1024) {
                throw new IllegalArgumentException("工作目录路径过长");
            }
            if (workspace.contains("..")) {
                throw new IllegalArgumentException("工作目录路径不允许包含 '..'");
            }
            Path path = Path.of(workspace);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("工作目录必须是绝对路径");
            }
        }
        UserSettings current = settingsRepository.getSettings();
        UserSettings updated = new UserSettings(
                current.theme(), current.language(),
                current.enableStreaming(), current.enableFunctionCall(),
                current.enableKnowledgeBase(), current.enableToolCall(),
                workspace
        );
        settingsRepository.save(updated);
        log.info("工作目录设置已更新: workspace={}", workspace);
        return getWorkspaceSettings();
    }

    @GetMapping("/channels")
    public ApiResponse<Map<String, Object>> getChannelConfig() {
        Map<String, Object> config = deserializeJsonConfig(settingsRepository.getChannelConfig());
        maskChannelSecrets(config);
        return ApiResponse.ok(config);
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/channels")
    public ApiResponse<Map<String, Object>> updateChannelConfig(@RequestBody Map<String, Object> request) {
        Map<String, Object> existing = deserializeJsonConfig(settingsRepository.getChannelConfig());

        for (var entry : request.entrySet()) {
            String channelKey = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> newChannelMap)) {
                continue;
            }
            Map<String, Object> merged = new HashMap<>((Map<String, Object>) newChannelMap);
            Object existingObj = existing.get(channelKey);
            for (String field : SENSITIVE_CHANNEL_FIELDS) {
                Object newValue = merged.get(field);
                if (newValue instanceof String text && isApiKeyMasked(text)) {
                    if (existingObj instanceof Map<?, ?> existingMap) {
                        Object originalValue = ((Map<String, Object>) existingMap).get(field);
                        if (originalValue instanceof String original && !isApiKeyMasked(original)) {
                            merged.put(field, original);
                            continue;
                        }
                    }
                    merged.remove(field);
                }
            }
            existing.put(channelKey, merged);
        }

        settingsRepository.saveChannelConfig(writeJson(existing));
        maskChannelSecrets(existing);
        log.info("渠道设置已更新: channels={}", existing.keySet());
        return ApiResponse.ok(existing);
    }

    @SuppressWarnings("unchecked")
    private void maskChannelSecrets(Map<String, Object> config) {
        for (var entry : config.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> channelMap)) {
                continue;
            }
            Map<String, Object> channel = (Map<String, Object>) channelMap;
            for (String field : SENSITIVE_CHANNEL_FIELDS) {
                Object value = channel.get(field);
                if (value instanceof String text && !text.isBlank()) {
                    channel.put(field, maskApiKey(text));
                }
            }
        }
    }

    private Map<String, Object> deserializeJsonConfig(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? new HashMap<>(result) : new HashMap<>();
        } catch (Exception e) {
            log.warn("JSON 配置反序列化失败: error={}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("设置序列化失败: " + e.getMessage(), e);
        }
    }

    private String maskApiKey(@Nullable String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****" + apiKey;
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    private boolean isApiKeyMasked(@Nullable String apiKey) {
        return apiKey != null && apiKey.startsWith("****");
    }

    private String normalizeSearchProvider(@Nullable String provider) {
        return "tavily";
    }

    private String normalizeSearchDepth(@Nullable String searchDepth) {
        if ("advanced".equalsIgnoreCase(searchDepth)) {
            return "advanced";
        }
        return "basic";
    }

    private String normalizeSearchTopic(@Nullable String topic) {
        if ("news".equalsIgnoreCase(topic)) {
            return "news";
        }
        if ("finance".equalsIgnoreCase(topic)) {
            return "finance";
        }
        return "general";
    }

    @SuppressWarnings("unchecked")
    private <T> T getConfigValue(Map<String, Object> config, String key, Class<T> type, T defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (type == Integer.class && value instanceof Number number) {
            return (T) Integer.valueOf(number.intValue());
        }
        if (type == Long.class && value instanceof Number number) {
            return (T) Long.valueOf(number.longValue());
        }
        if (type == Double.class && value instanceof Number number) {
            return (T) Double.valueOf(number.doubleValue());
        }
        if (type == Boolean.class && value instanceof String text) {
            return (T) Boolean.valueOf(Boolean.parseBoolean(text));
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
