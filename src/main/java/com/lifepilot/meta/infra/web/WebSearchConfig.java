package com.lifepilot.meta.infra.web;

/**
 * 联网搜索运行时配置。
 *
 * @param provider              搜索提供商
 * @param apiKey                Tavily API Key
 * @param maxResults            最大返回结果数
 * @param connectTimeoutSeconds 连接超时（秒）
 * @param readTimeoutSeconds    读取超时（秒）
 * @param searchDepth           搜索深度（basic / advanced）
 * @param topic                 搜索主题（general / news / finance）
 * @param includeAnswer         是否返回 Tavily answer 摘要
 * @author zsg
 * @since 2026-03-20
 */
public record WebSearchConfig(
        String provider,
        String apiKey,
        int maxResults,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        String searchDepth,
        String topic,
        boolean includeAnswer
) {
}
