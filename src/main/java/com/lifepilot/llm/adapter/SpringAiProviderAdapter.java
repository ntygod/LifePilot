package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.multimodal.MediaContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Spring AI 的统一 Provider 适配器。
 *
 * <p>封装 {@link ChatModel}、{@link EmbeddingModel}（可选）和 {@link ChatClient}（延迟构建），
 * 提供统一的调用接口。
 *
 * @author zsg
 * @since 2026-02-24
 */
public final class SpringAiProviderAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(SpringAiProviderAdapter.class);

    private final ProviderConfig config;
    private final ChatModel chatModel;
    @Nullable
    private final EmbeddingModel embeddingModel;
    private final List<CallAdvisor> defaultAdvisors;

    // 延迟构建的 ChatClient
    @Nullable
    private volatile ChatClient chatClient;

    /**
     * 创建 Spring AI Provider 适配器。
     *
     * @param config         Provider 配置
     * @param chatModel      Chat 模型
     * @param embeddingModel Embedding 模型（可选）
     * @param defaultAdvisors 默认 Advisor 列表（可选）
     */
    public SpringAiProviderAdapter(ProviderConfig config,
                                   ChatModel chatModel,
                                   @Nullable EmbeddingModel embeddingModel,
                                   @Nullable List<CallAdvisor> defaultAdvisors) {
        this.config = config;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.defaultAdvisors = defaultAdvisors != null ? List.copyOf(defaultAdvisors) : List.of();
    }

    @Override
    public LlmResponse call(String prompt, @Nullable String outputSchema, Duration timeout) {
        long start = System.currentTimeMillis();
        ChatResponse response = chatModel.call(new Prompt(prompt));
        long latencyMs = System.currentTimeMillis() - start;

        var usage = response.getMetadata().getUsage();
        int inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        String content = response.getResult().getOutput().getText();

        return new LlmResponse(
                content,
                inputTokens,
                outputTokens,
                config.id(),
                config.modelName(),
                latencyMs,
                false
        );
    }

    @Override
    public <T> T callEntity(String prompt, Class<T> responseType) {
        return buildChatClient()
                .prompt(prompt)
                .call()
                .entity(responseType);
    }

    @Override
    public float[] embed(String text) {
        if (embeddingModel == null || !config.hasCapability(ProviderCapability.EMBEDDING)) {
            throw new UnsupportedOperationException(
                    "Provider 不支持 EMBEDDING 能力: id=" + config.id());
        }
        return embeddingModel.embed(text);
    }

    @Override
    public Flux<String> stream(String prompt) {
        if (!config.supportsStreaming()) {
            throw new UnsupportedOperationException(
                    "Provider 不支持 STREAMING 能力: id=" + config.id());
        }
        return chatModel.stream(new Prompt(prompt))
                .mapNotNull(response -> {
                    var result = response.getResult();
                    return result.getOutput().getText();
                })
                .filter(text -> text != null && !text.isEmpty());
    }

    @Override
    public Optional<ChatClient> chatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    chatClient = buildChatClient();
                }
            }
        }
        return Optional.of(chatClient);
    }

    /**
     * 构建 ChatClient，注入默认 Advisor 链。
     * Advisor 执行顺序：GuardrailAdvisor(100) → TraceAdvisor(200)
     */
    private ChatClient buildChatClient() {
        var builder = ChatClient.builder(chatModel);
        if (!defaultAdvisors.isEmpty()) {
            builder.defaultAdvisors(defaultAdvisors.toArray(new CallAdvisor[0]));
        }
        return builder.build();
    }

    @Override
    public boolean healthCheck() {
        try {
            // 如果 Provider 有 CHAT 能力，使用 chatModel 进行健康检查
            if (config.hasCapability(ProviderCapability.CHAT)) {
                ChatResponse response = chatModel.call(new Prompt("ping"));
                if (response == null || response.getResult() == null
                        || response.getResult().getOutput() == null) {
                    return false;
                }
                String text = response.getResult().getOutput().getText();
                return text != null && !text.isBlank();
            }
            // 如果 Provider 只有 EMBEDDING 能力（纯 embedding Provider），使用 embeddingModel 进行健康检查
            else if (config.hasCapability(ProviderCapability.EMBEDDING) && embeddingModel != null) {
                float[] embedding = embeddingModel.embed("ping");
                return embedding.length > 0;
            }
            // 既没有 CHAT 也没有 EMBEDDING 能力，视为不健康
            else {
                log.debug("Provider 既没有 CHAT 也没有 EMBEDDING 能力: id={}", config.id());
                return false;
            }
        } catch (Exception e) {
            // 连接失败是常见情况，使用 DEBUG 级别避免过多日志
            // 仅在 ProviderHealthChecker 中记录汇总信息
            log.debug("Provider 健康检查失败: id={}, error={}", config.id(), e.getMessage());
            return false;
        }
    }

    @Override
    public LlmResponse callWithMedia(String prompt, List<MediaContent> mediaContents, Duration timeout) {
        if (!config.hasCapability(ProviderCapability.VISION)) {
            throw new UnsupportedOperationException("Provider 不支持 VISION 能力: " + config.id());
        }

        // 将 MediaContent 列表转换为 Spring AI Media 对象列表
        List<Media> mediaList = mediaContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        // 构建多模态消息
        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        long start = System.currentTimeMillis();
        ChatResponse response = chatModel.call(new Prompt(userMessage));
        long latencyMs = System.currentTimeMillis() - start;

        var usage = response.getMetadata().getUsage();
        int inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        String content = response.getResult().getOutput().getText();

        return new LlmResponse(
                content,
                inputTokens,
                outputTokens,
                config.id(),
                config.modelName(),
                latencyMs,
                false
        );
    }

    @Override
    public Flux<String> streamWithMedia(String prompt, List<MediaContent> mediaContents) {
        if (!config.hasCapability(ProviderCapability.VISION)) {
            throw new UnsupportedOperationException("Provider 不支持 VISION 能力: " + config.id());
        }

        // 将 MediaContent 列表转换为 Spring AI Media 对象列表
        List<Media> mediaList = mediaContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        // 构建多模态消息
        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        return chatModel.stream(new Prompt(userMessage))
                .mapNotNull(response -> {
                    var result = response.getResult();
                    return result.getOutput().getText();
                })
                .filter(text -> text != null && !text.isEmpty());
    }

    /**
     * 获取 Provider 配置。
     *
     * @return Provider 配置
     */
    public ProviderConfig config() {
        return config;
    }

    /**
     * 获取底层 ChatModel。
     *
     * @return ChatModel 实例
     */
    public ChatModel chatModel() {
        return chatModel;
    }
}
