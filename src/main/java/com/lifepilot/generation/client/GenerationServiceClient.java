package com.lifepilot.generation.client;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.stream.LlmStreamEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 生成服务客户端。
 *
 * @author zsg
 * @since 2026-03-24
 */
public interface GenerationServiceClient {

    /**
     * 文本生成。
     *
     * @param prompt 提示词
     * @param outputSchema 输出 schema
     * @param timeoutOverride 超时覆盖
     * @return 响应
     */
    LlmResponse call(String prompt, @Nullable String outputSchema, @Nullable Duration timeoutOverride);

    /**
     * 结构化输出。
     *
     * @param prompt 提示词
     * @param responseType 响应类型
     * @param timeoutOverride 超时覆盖
     * @param <T> 泛型
     * @return 结构化对象
     */
    <T> T callEntity(String prompt, Class<T> responseType, @Nullable Duration timeoutOverride);

    /**
     * 获取 ChatClient。
     *
     * @return ChatClient
     */
    Optional<ChatClient> chatClient();

    /**
     * 获取底层 ChatModel。
     *
     * @return ChatModel
     */
    ChatModel chatModel();

    /**
     * 流式调用。
     *
     * @param prompt 提示词
     * @return 文本流
     */
    Flux<String> stream(String prompt);

    /**
     * 流式调用并发出 {@link LlmStreamEvent} 事件序列。
     *
     * <p>替代仅产 {@code Flux<String>} 的 {@link #stream(String)}，承载 reasoning_content /
     * tool_calls / usage / done / error 等多维事件。
     *
     * @param prompt        Spring AI Prompt
     * @param toolCallbacks 工具回调列表（当前未使用，保留扩展位）
     * @return LlmStreamEvent 流
     */
    Flux<LlmStreamEvent> streamEvents(Prompt prompt, List<ToolCallback> toolCallbacks);
}
