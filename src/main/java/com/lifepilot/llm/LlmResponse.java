package com.lifepilot.llm;

/**
 * LLM 调用统一响应。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record LlmResponse(
        String content,
        int inputTokens,
        int outputTokens,
        String providerId,
        String modelName,
        long latencyMs,
        boolean cached
) {

    /**
     * 总 Token 数。
     *
     * @return inputTokens + outputTokens
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * 创建缓存命中响应（Token 数和延迟均为 0）。
     *
     * @param content    响应内容
     * @param providerId Provider ID
     * @param modelName  模型名称
     * @return 缓存响应
     */
    public static LlmResponse cached(String content, String providerId, String modelName) {
        return new LlmResponse(content, 0, 0, providerId, modelName, 0, true);
    }
}
