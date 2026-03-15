package com.lifepilot.knowledge.rerank;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Reranker 配置提供者 — 运行时从数据库读取配置，覆盖 application.yml 默认值。
 *
 * <p>每次调用 {@link #getConfig()} 都会从数据库读取最新配置，
 * 确保 UI 修改后立即生效，无需重启。
 *
 * @author zsg
 * @since 2026-03-15
 */
public class RerankerConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(RerankerConfigProvider.class);

    private final Supplier<String> dbConfigReader;
    private final KnowledgeBaseProperties.Reranker defaults;
    private final ObjectMapper objectMapper;

    /**
     * 创建 RerankerConfigProvider。
     *
     * @param dbConfigReader 从数据库读取 reranker_config_json 的函数
     * @param defaults       application.yml 中的默认配置
     * @param objectMapper   JSON 序列化器
     */
    public RerankerConfigProvider(Supplier<String> dbConfigReader,
                                  KnowledgeBaseProperties.Reranker defaults,
                                  ObjectMapper objectMapper) {
        this.dbConfigReader = dbConfigReader;
        this.defaults = defaults;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前生效的 Reranker 配置（DB 覆盖 application.yml 默认值）。
     *
     * @return 合并后的 Reranker 配置
     */
    public KnowledgeBaseProperties.Reranker getConfig() {
        Map<String, Object> dbConfig = readDbConfig();
        if (dbConfig.isEmpty()) {
            return defaults;
        }
        return new KnowledgeBaseProperties.Reranker(
                getVal(dbConfig, "enabled", Boolean.class, defaults.enabled()),
                getVal(dbConfig, "type", String.class, defaults.type()),
                getVal(dbConfig, "model", String.class, defaults.model()),
                getVal(dbConfig, "topK", Integer.class, defaults.topK()),
                getVal(dbConfig, "llmMode", String.class, defaults.llmMode()),
                getVal(dbConfig, "listwiseMaxCandidates", Integer.class, defaults.listwiseMaxCandidates()),
                getVal(dbConfig, "apiProvider", String.class, defaults.apiProvider()),
                getVal(dbConfig, "apiKey", String.class, defaults.apiKey()),
                getVal(dbConfig, "apiEndpoint", String.class, defaults.apiEndpoint()),
                getVal(dbConfig, "apiTimeoutMs", Integer.class, defaults.apiTimeoutMs())
        );
    }

    /**
     * 获取记忆精排是否启用（DB 覆盖 application.yml）。
     *
     * @param memoryRerankerDefaults 记忆精排默认配置
     * @return 是否启用
     */
    public boolean isMemoryRerankEnabled(boolean memoryRerankerDefaults) {
        Map<String, Object> dbConfig = readDbConfig();
        return getVal(dbConfig, "memoryRerankEnabled", Boolean.class, memoryRerankerDefaults);
    }

    /**
     * 获取记忆精排 topK（DB 覆盖 application.yml）。
     *
     * @param memoryRerankerDefaultTopK 记忆精排默认 topK
     * @return topK
     */
    public int getMemoryRerankTopK(int memoryRerankerDefaultTopK) {
        Map<String, Object> dbConfig = readDbConfig();
        return getVal(dbConfig, "memoryRerankTopK", Integer.class, memoryRerankerDefaultTopK);
    }

    private Map<String, Object> readDbConfig() {
        try {
            String json = dbConfigReader.get();
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return Map.of();
            }
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("读取数据库 Reranker 配置失败，使用默认值: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getVal(Map<String, Object> config, String key, Class<T> type, T defaultValue) {
        Object value = config.get(key);
        if (value == null) return defaultValue;
        if (type == Integer.class && value instanceof Number number) {
            return (T) Integer.valueOf(number.intValue());
        }
        if (type.isInstance(value)) return type.cast(value);
        return defaultValue;
    }
}
