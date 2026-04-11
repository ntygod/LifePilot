package com.lifepilot.meta.infra.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.meta.config.MetaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 联网搜索配置提供者。
 *
 * <p>每次调用都会从数据库读取最新配置，并覆盖 application.yml 默认值，
 * 确保前端设置页修改后立即生效，无需重启。
 *
 * @author zsg
 * @since 2026-03-20
 */
public class WebSearchConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(WebSearchConfigProvider.class);

    private final Supplier<String> dbConfigReader;
    private final MetaProperties.Infra.WebSearch defaults;
    private final ObjectMapper objectMapper;

    public WebSearchConfigProvider(Supplier<String> dbConfigReader,
                                   MetaProperties.Infra.WebSearch defaults,
                                   ObjectMapper objectMapper) {
        this.dbConfigReader = dbConfigReader;
        this.defaults = defaults;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取当前生效的联网搜索配置。
     */
    public WebSearchConfig getConfig() {
        Map<String, Object> dbConfig = readDbConfig();

        String provider = normalizeProvider(getVal(dbConfig, "provider", String.class, defaults.getProvider()));
        String apiKey = normalizeApiKey(getVal(dbConfig, "apiKey", String.class, defaults.getApiKey()));
        int maxResults = clamp(getVal(dbConfig, "maxResults", Integer.class, defaults.getMaxResults()), 1, 20);
        int connectTimeoutSeconds = Math.max(1,
                getVal(dbConfig, "connectTimeoutSeconds", Integer.class, defaults.getConnectTimeoutSeconds()));
        int readTimeoutSeconds = Math.max(1,
                getVal(dbConfig, "readTimeoutSeconds", Integer.class, defaults.getReadTimeoutSeconds()));
        String searchDepth = normalizeSearchDepth(
                getVal(dbConfig, "searchDepth", String.class, defaults.getSearchDepth()));
        String topic = normalizeTopic(getVal(dbConfig, "topic", String.class, defaults.getTopic()));
        boolean includeAnswer = getVal(dbConfig, "includeAnswer", Boolean.class, defaults.isIncludeAnswer());

        String apiUrl = getVal(dbConfig, "apiUrl", String.class, defaults.getApiUrl());

        return new WebSearchConfig(
                apiUrl,
                provider,
                apiKey,
                maxResults,
                connectTimeoutSeconds,
                readTimeoutSeconds,
                searchDepth,
                topic,
                includeAnswer
        );
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
            log.warn("读取数据库联网搜索配置失败，使用默认值: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String normalizeProvider(String provider) {
        return "tavily";
    }

    private static String normalizeApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("****")) {
            return "";
        }
        return apiKey.trim();
    }

    private static String normalizeSearchDepth(String searchDepth) {
        if ("advanced".equalsIgnoreCase(searchDepth)) {
            return "advanced";
        }
        return "basic";
    }

    private static String normalizeTopic(String topic) {
        if ("news".equalsIgnoreCase(topic)) {
            return "news";
        }
        if ("finance".equalsIgnoreCase(topic)) {
            return "finance";
        }
        return "general";
    }

    @SuppressWarnings("unchecked")
    private <T> T getVal(Map<String, Object> config, String key, Class<T> type, T defaultValue) {
        Object value = config.get(key);
        switch (value) {
            case null -> {
                return defaultValue;
            }
            case Number number when type == Integer.class -> {
                return (T) Integer.valueOf(number.intValue());
            }
            case String s when type == Boolean.class -> {
                return (T) Boolean.valueOf(Boolean.parseBoolean(s));
            }
            default -> {
            }
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
