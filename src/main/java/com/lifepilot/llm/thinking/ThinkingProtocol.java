package com.lifepilot.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.llm.history.AssistantMessageBuilder;
import com.lifepilot.llm.profile.ThinkingProtocolId;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 推理模式协议处理器。
 *
 * <p>每个实现承载一家 provider 的 thinking 协议差异，通过三个方法表达：
 * <ol>
 *   <li>{@link #applyToRequest(RequestBuilder, ThinkingMode)} — 把 mode 转换成厂商私有字段；</li>
 *   <li>{@link #extractReasoning(JsonNode)} — 从响应（同步或流式 chunk）提取 reasoning_content；</li>
 *   <li>{@link #injectHistoryReasoning(AssistantMessageBuilder, Map)} — 多轮回传时按协议注入 reasoning。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-27
 */
public interface ThinkingProtocol {

    ThinkingProtocolId id();

    /**
     * 把 thinking_mode 转换成厂商私有字段。
     *
     * @param builder 可变请求构造器
     * @param mode    用户配置的 thinking_mode
     */
    void applyToRequest(RequestBuilder builder, ThinkingMode mode);

    /**
     * 从响应 JSON 提取 reasoning_content。
     *
     * @param rawResponseChunk 完整响应或单个流式 chunk 的 JSON 树
     * @return reasoning 文本，无则 null
     */
    @Nullable
    String extractReasoning(JsonNode rawResponseChunk);

    /**
     * 从响应 JSON 提取 signature（仅 Anthropic 使用）。
     *
     * @param rawResponseChunk 响应 JSON
     * @return signature，无则 null
     */
    @Nullable
    default String extractReasoningSignature(JsonNode rawResponseChunk) {
        return null;
    }

    /**
     * 多轮回传：上一轮 assistant 的 reasoning 注入到 history message。
     *
     * @param builder      assistant 消息构造器
     * @param prevPayload  上一轮 assistant 的 payload_json 反序列化结果
     */
    void injectHistoryReasoning(AssistantMessageBuilder builder, Map<String, Object> prevPayload);
}
