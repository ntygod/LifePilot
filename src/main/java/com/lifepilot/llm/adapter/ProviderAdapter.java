package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;

/**
 * LLM Provider 适配器密封接口。
 *
 * <p>统一封装不同 Provider 的调用方式，仅允许 {@link SpringAiProviderAdapter} 实现。
 *
 * @author zsg
 * @since 2026-02-24
 */
public sealed interface ProviderAdapter permits SpringAiProviderAdapter {

    /**
     * 执行文本生成调用。
     *
     * @param prompt       提示词
     * @param outputSchema 输出 Schema（可选，用于结构化输出）
     * @param timeout      超时时间
     * @return 统一响应
     */
    LlmResponse call(String prompt, @Nullable String outputSchema, Duration timeout);

    /**
     * 执行结构化输出调用。
     *
     * @param prompt       提示词
     * @param responseType 响应类型
     * @param <T>          响应泛型
     * @return 结构化响应对象
     */
    <T> T callEntity(String prompt, Class<T> responseType);

    /**
     * 执行文本嵌入。
     *
     * @param text 待嵌入文本
     * @return 嵌入向量
     * @throws UnsupportedOperationException 若 Provider 不支持 EMBEDDING 能力
     */
    float[] embed(String text);

    /**
     * 执行流式文本生成。
     *
     * @param prompt 提示词
     * @return 流式文本响应
     * @throws UnsupportedOperationException 若 Provider 不支持 STREAMING 能力
     */
    Flux<String> stream(String prompt);

    /**
     * 获取 ChatClient 实例（延迟构建）。
     *
     * @return ChatClient，不支持时返回 empty
     */
    Optional<ChatClient> chatClient();

    /**
     * 执行健康检查。
     *
     * @return 健康返回 true
     */
    boolean healthCheck();
}
