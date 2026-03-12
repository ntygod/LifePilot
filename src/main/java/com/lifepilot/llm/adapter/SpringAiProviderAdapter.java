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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Spring AI 的统一 Provider 适配器。
 *
 * <p>封装 {@link ChatModel}、{@link EmbeddingModel}（可选）和 {@link ChatClient}（延迟构建），
 * 提供统一的调用接口，支持超时控制。
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

    @Nullable
    private volatile ChatClient chatClient;

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
        ChatResponse response = executeWithTimeout(() -> chatModel.call(new Prompt(prompt)), timeout);
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
                .mapNotNull(response -> response.getResult().getOutput().getText())
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
            if (config.hasCapability(ProviderCapability.CHAT)) {
                ChatResponse response = chatModel.call(new Prompt("ping"));
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    return false;
                }
                String text = response.getResult().getOutput().getText();
                return text != null && !text.isBlank();
            }
            if (config.hasCapability(ProviderCapability.EMBEDDING) && embeddingModel != null) {
                float[] embedding = embeddingModel.embed("ping");
                return embedding.length > 0;
            }
            log.debug("Provider 既没有 CHAT 也没有 EMBEDDING 能力: id={}", config.id());
            return false;
        } catch (Exception e) {
            log.debug("Provider 健康检查失败: id={}, error={}", config.id(), e.getMessage());
            return false;
        }
    }

    @Override
    public LlmResponse callWithMedia(String prompt, List<MediaContent> mediaContents, Duration timeout) {
        if (!config.hasCapability(ProviderCapability.VISION)) {
            throw new UnsupportedOperationException("Provider 不支持 VISION 能力: " + config.id());
        }

        List<Media> mediaList = mediaContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        long start = System.currentTimeMillis();
        ChatResponse response = executeWithTimeout(() -> chatModel.call(new Prompt(userMessage)), timeout);
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

        List<Media> mediaList = mediaContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        return chatModel.stream(new Prompt(userMessage))
                .mapNotNull(response -> response.getResult().getOutput().getText())
                .filter(text -> text != null && !text.isEmpty());
    }

    public ProviderConfig config() {
        return config;
    }

    public ChatModel chatModel() {
        return chatModel;
    }

    @Nullable
    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    private <T> T executeWithTimeout(Callable<T> action, Duration timeout) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(action);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RuntimeException("LLM 调用超时: " + timeout.toSeconds() + "s", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM 调用被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(cause);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败", e);
        }
    }
}
