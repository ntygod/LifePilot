package com.lifepilot.interaction.web.model;

/**
 * 联网搜索配置响应。
 *
 * <p>当前仅支持 Tavily。{@code apiKey} 返回掩码值（如 {@code ****abcd}），
 * 不暴露原始密钥。
 *
 * @param provider              搜索提供商
 * @param apiKey                Tavily API Key（掩码值）
 * @param maxResults            最大返回结果数
 * @param searchDepth           搜索深度（basic / advanced）
 * @param topic                 搜索主题（general / news / finance）
 * @param includeAnswer         是否返回 Tavily answer 摘要
 * @param connectTimeoutSeconds 连接超时（秒）
 * @param readTimeoutSeconds    读取超时（秒）
 * @author zsg
 * @since 2026-03-20
 */
public record SearchSettingsResponse(
        String provider,
        String apiKey,
        int maxResults,
        String searchDepth,
        String topic,
        boolean includeAnswer,
        int connectTimeoutSeconds,
        int readTimeoutSeconds
) {
}
