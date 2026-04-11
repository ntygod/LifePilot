package com.lifepilot.llm;

import reactor.core.publisher.Flux;

/**
 * 流式 LLM 响应包装 —— 附带路由选择的 Provider 与模型信息。
 *
 * <p>用于在 SSE 流式通道中补齐可观测性数据（modelId/token usage），
 * 避免仅返回 {@code Flux<String>} 时丢失元信息。</p>
 *
 * @param stream     token/片段流
 * @param providerId Provider ID（配置 id）
 * @param modelId    模型 ID（配置 modelName）
 * @author zsg
 * @since 2026-03-05
 */
public record StreamingLlmResponse(
        Flux<String> stream,
        String providerId,
        String modelId
) {
}

