package com.lifepilot.interaction.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 渠道配置提供者 — 优先从数据库读取，fallback 到 application.yml。
 *
 * <p>数据库中的渠道配置存储在 user_settings.channel_config_json 列，
 * 格式为 {@code {"feishu": {"enabled": true, "appId": "...", ...}, "wecom": {...}}}。
 * 当数据库中没有某个渠道的配置时，使用 GatewayProperties 中的 yml 默认值。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
@Component
@ConditionalOnProperty(name = "lifepilot.gateway.enabled", havingValue = "true", matchIfMissing = true)
public class ChannelConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(ChannelConfigProvider.class);

    private final UserSettingsRepository settingsRepository;
    private final GatewayProperties gatewayProperties;
    private final ObjectMapper objectMapper;

    public ChannelConfigProvider(UserSettingsRepository settingsRepository,
                                 GatewayProperties gatewayProperties,
                                 ObjectMapper objectMapper) {
        this.settingsRepository = settingsRepository;
        this.gatewayProperties = gatewayProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取飞书渠道的有效配置（数据库优先，fallback yml）。
     */
    public GatewayProperties.ChannelsProperties.FeishuChannelProperties getFeishuConfig() {
        var dbConfig = loadChannelFromDb("feishu");
        var ymlConfig = gatewayProperties.channels().feishu();
        if (dbConfig == null || dbConfig.isEmpty()) {
            return ymlConfig;
        }
        return new GatewayProperties.ChannelsProperties.FeishuChannelProperties(
                getBool(dbConfig, "enabled", ymlConfig.enabled()),
                getStr(dbConfig, "appId", ymlConfig.appId()),
                getStrSensitive(dbConfig, "appSecret", ymlConfig.appSecret()),
                getStrSensitive(dbConfig, "verificationToken", ymlConfig.verificationToken()),
                getStrSensitive(dbConfig, "encryptKey", ymlConfig.encryptKey()),
                getInt(dbConfig, "eventCacheMaxSize", ymlConfig.eventCacheMaxSize())
        );
    }

    /**
     * 获取企微渠道的有效配置。
     */
    public GatewayProperties.ChannelsProperties.WecomChannelProperties getWecomConfig() {
        var dbConfig = loadChannelFromDb("wecom");
        var ymlConfig = gatewayProperties.channels().wecom();
        if (dbConfig == null || dbConfig.isEmpty()) {
            return ymlConfig;
        }
        return new GatewayProperties.ChannelsProperties.WecomChannelProperties(
                getBool(dbConfig, "enabled", ymlConfig.enabled()),
                getStr(dbConfig, "corpId", ymlConfig.corpId()),
                getStr(dbConfig, "agentId", ymlConfig.agentId()),
                getStrSensitive(dbConfig, "secret", ymlConfig.secret()),
                getStrSensitive(dbConfig, "token", ymlConfig.token()),
                getStrSensitive(dbConfig, "encodingAesKey", ymlConfig.encodingAesKey())
        );
    }

    /**
     * 获取钉钉渠道的有效配置。
     */
    public GatewayProperties.ChannelsProperties.DingtalkChannelProperties getDingtalkConfig() {
        var dbConfig = loadChannelFromDb("dingtalk");
        var ymlConfig = gatewayProperties.channels().dingtalk();
        if (dbConfig == null || dbConfig.isEmpty()) {
            return ymlConfig;
        }
        return new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(
                getBool(dbConfig, "enabled", ymlConfig.enabled()),
                getStr(dbConfig, "appKey", ymlConfig.appKey()),
                getStrSensitive(dbConfig, "appSecret", ymlConfig.appSecret()),
                getStr(dbConfig, "robotCode", ymlConfig.robotCode())
        );
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    @Nullable
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadChannelFromDb(String channelKey) {
        try {
            String json = settingsRepository.getChannelConfig();
            if (json == null || json.isBlank() || "{}".equals(json)) {
                return null;
            }
            Map<String, Object> all = objectMapper.readValue(json, new TypeReference<>() {});
            Object channelObj = all.get(channelKey);
            if (channelObj instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return null;
        } catch (Exception e) {
            log.warn("从数据库加载渠道配置失败: channel={}, error={}", channelKey, e.getMessage());
            return null;
        }
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean defaultVal) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }

    @Nullable
    private static String getStr(Map<String, Object> map, String key, @Nullable String defaultVal) {
        Object v = map.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        return defaultVal;
    }

    /**
     * 读取敏感字段值，如果是掩码值（****开头）则 fallback 到 yml 默认值。
     *
     * <p>防止前端返回的掩码值被误用为真实凭证。
     */
    @Nullable
    private String getStrSensitive(Map<String, Object> map, String key, @Nullable String defaultVal) {
        Object v = map.get(key);
        if (v instanceof String s && !s.isBlank()) {
            if (s.startsWith("****")) {
                log.debug("数据库中渠道配置字段 {} 为掩码值，fallback 到 yml 默认值", key);
                return defaultVal;
            }
            return s;
        }
        return defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }
}
