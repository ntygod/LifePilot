package com.lifepilot.llm;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 调用统一响应（富字段版本）。
 *
 * <p>承载推理模型多轮契约所需的全部字段：reasoning_content / reasoning_signature /
 * tool_calls / provider_metadata / reasoning_tokens / cached_input_tokens。
 *
 * <p>迁移期通过 {@link #simple} 静态工厂兼容老调用点（仅 content + tokens 维度）。
 *
 * @param content             响应正文（assistant message text）
 * @param reasoningContent    推理过程文本（DeepSeek V4 / Qwen3 等推理模型返回，可空）
 * @param reasoningSignature  推理签名（仅 Anthropic thinking block 使用，可空）
 * @param toolCalls           Provider 返回的工具调用列表（不可空，缺省 List.of()）
 * @param providerMetadata    厂商私有元数据（多轮回传需要，缺省 Map.of()）
 * @param inputTokens         输入 token 数
 * @param outputTokens        输出 token 数
 * @param reasoningTokens     推理 token 数（OpenAI 等返回，可空）
 * @param cachedInputTokens   prompt cache 命中的 token 数
 * @param providerId          Provider ID
 * @param modelName           模型名称
 * @param latencyMs           调用耗时（毫秒）
 * @param cached              是否命中语义缓存
 * @author zsg
 * @since 2026-04-27
 */
public record LlmResponse(
        String content,
        @Nullable String reasoningContent,
        @Nullable String reasoningSignature,
        List<ToolCall> toolCalls,
        Map<String, Object> providerMetadata,
        int inputTokens,
        int outputTokens,
        @Nullable Integer reasoningTokens,
        int cachedInputTokens,
        String providerId,
        String modelName,
        long latencyMs,
        boolean cached
) {

    public LlmResponse {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        providerMetadata = providerMetadata != null ? Map.copyOf(providerMetadata) : Map.of();
    }

    /**
     * 总 Token 数（含 reasoning tokens 折算）。
     *
     * @return inputTokens + outputTokens + reasoningTokens
     */
    public int totalTokens() {
        return inputTokens + outputTokens + Optional.ofNullable(reasoningTokens).orElse(0);
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
        return new LlmResponse(
                content, null, null, List.of(), Map.of(),
                0, 0, null, 0, providerId, modelName, 0, true);
    }

    /**
     * 兼容老调用：仅含 content + tokens 的简单构造（迁移期使用）。
     *
     * <p>新代码应直接调用富字段构造器或由 {@link com.lifepilot.llm.adapter.AbstractProviderAdapter#toLlmResponse}
     * 等转换器构造。
     *
     * @param content     响应正文
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     * @param providerId  Provider ID
     * @param modelName   模型名称
     * @param latencyMs   调用耗时
     * @return 简化版响应
     */
    public static LlmResponse simple(String content,
                                     int inputTokens,
                                     int outputTokens,
                                     String providerId,
                                     String modelName,
                                     long latencyMs) {
        return new LlmResponse(
                content, null, null, List.of(), Map.of(),
                inputTokens, outputTokens, null, 0,
                providerId, modelName, latencyMs, false);
    }
}
