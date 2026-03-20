package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 联网搜索配置更新请求。
 *
 * <p>当前仅支持 Tavily，所有字段均为可选，未提供的字段在更新时保留原值。
 *
 * @param provider              搜索提供商，固定为 tavily
 * @param apiKey                Tavily API Key
 * @param maxResults            最大返回结果数
 * @param searchDepth           搜索深度（basic / advanced）
 * @param topic                 搜索主题（general / news / finance）
 * @param includeAnswer         是否返回 Tavily answer 摘要
 * @param connectTimeoutSeconds 连接超时（秒）
 * @param readTimeoutSeconds    读取超时（秒）
 * @author zsg
 * @since 2026-03-20
 */
public record SearchSettingsRequest(
        @Nullable String provider,
        @Nullable String apiKey,
        @Nullable Integer maxResults,
        @Nullable String searchDepth,
        @Nullable String topic,
        @Nullable Boolean includeAnswer,
        @Nullable Integer connectTimeoutSeconds,
        @Nullable Integer readTimeoutSeconds
) {
}
