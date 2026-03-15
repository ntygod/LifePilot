package com.lifepilot.interaction.web.model;

/**
 * Reranker 全局配置响应。
 *
 * <p>包含全局精排配置和记忆精排配置的当前值。
 * {@code apiKey} 字段返回掩码值（如 {@code ****abcd}），不暴露原始密钥。
 *
 * @param enabled              全局精排开关
 * @param type                 精排类型（llm / api）
 * @param model                模型名
 * @param topK                 返回数量
 * @param llmMode              LLM 模式（pointwise / listwise）
 * @param apiProvider          API 提供商（jina / cohere）
 * @param apiKey               API Key（掩码值，仅保留末尾 4 位，前缀为 {@code ****}）
 * @param apiEndpoint          API 端点
 * @param apiTimeoutMs         API 超时（毫秒）
 * @param memoryRerankEnabled  记忆精排开关
 * @param memoryRerankTopK     记忆精排 topK
 * @author zsg
 * @since 2026-03-15
 */
public record RerankerSettingsResponse(
        boolean enabled,
        String type,
        String model,
        int topK,
        String llmMode,
        String apiProvider,
        String apiKey,
        String apiEndpoint,
        int apiTimeoutMs,
        boolean memoryRerankEnabled,
        int memoryRerankTopK
) {
}
