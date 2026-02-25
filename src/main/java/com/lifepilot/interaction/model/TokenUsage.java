package com.lifepilot.interaction.model;

/**
 * Token 消耗统计 record。
 *
 * <p>记录单次请求的 Token 消耗情况，包括提示词 Token、补全 Token、总 Token 和模型标识。
 * 提供 {@link #ZERO} 静态常量用于不涉及 LLM 的快速路径响应。
 *
 * @param promptTokens     提示词 Token 数量
 * @param completionTokens 补全 Token 数量
 * @param totalTokens      总 Token 数量
 * @param modelId          模型标识
 * @author zsg
 * @since 2026-02-25
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens, String modelId) {

    /** 零 Token 消耗常量，用于不涉及 LLM 的快速路径响应。 */
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, "none");
}
