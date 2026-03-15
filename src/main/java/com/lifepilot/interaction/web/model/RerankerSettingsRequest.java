package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * Reranker 全局配置更新请求。
 *
 * <p>包含全局精排配置和记忆精排配置。所有字段均为可选，
 * 未提供的字段在更新时保留原值。
 *
 * @param enabled              全局精排开关
 * @param type                 精排类型（llm / api）
 * @param model                模型名
 * @param topK                 返回数量
 * @param llmMode              LLM 模式（pointwise / listwise）
 * @param apiProvider          API 提供商（jina / cohere）
 * @param apiKey               API Key
 * @param apiEndpoint          API 端点
 * @param apiTimeoutMs         API 超时（毫秒）
 * @param memoryRerankEnabled  记忆精排开关
 * @param memoryRerankTopK     记忆精排 topK
 * @author zsg
 * @since 2026-03-15
 */
public record RerankerSettingsRequest(
        @Nullable Boolean enabled,
        @Nullable String type,
        @Nullable String model,
        @Nullable Integer topK,
        @Nullable String llmMode,
        @Nullable String apiProvider,
        @Nullable String apiKey,
        @Nullable String apiEndpoint,
        @Nullable Integer apiTimeoutMs,
        @Nullable Boolean memoryRerankEnabled,
        @Nullable Integer memoryRerankTopK
) {
}
