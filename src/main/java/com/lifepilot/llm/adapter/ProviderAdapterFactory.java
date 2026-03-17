package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.LlmConfigProperties.ConnectionPoolConfigEntry;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Provider 适配器工厂。
 *
 * <p>根据 {@link ProviderType} 创建对应的 {@link SpringAiProviderAdapter}，
 * 手动构建 ChatModel/EmbeddingModel 实例。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ProviderAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderAdapterFactory.class);
    private static final int MIN_HTTP_TIMEOUT_SECONDS = 300;

    private final List<CallAdvisor> defaultAdvisors;
    @Nullable
    private final ConnectionPoolConfigEntry connectionPoolConfig;

    public ProviderAdapterFactory(@Nullable List<CallAdvisor> defaultAdvisors,
                                  @Nullable ConnectionPoolConfigEntry connectionPoolConfig) {
        this.defaultAdvisors = defaultAdvisors != null ? List.copyOf(defaultAdvisors) : List.of();
        this.connectionPoolConfig = connectionPoolConfig;
        if (!this.defaultAdvisors.isEmpty()) {
            log.info("ProviderAdapterFactory 初始化: 默认 Advisors={}",
                    this.defaultAdvisors.stream()
                            .map(a -> a.getClass().getSimpleName())
                            .toList());
        }
    }

    public ProviderAdapterFactory() {
        this.defaultAdvisors = List.of();
        this.connectionPoolConfig = null;
    }

    public SpringAiProviderAdapter create(ProviderConfig config) {
        ProviderType providerType = config.type();
        if (providerType == ProviderType.OLLAMA) {
            return createOllamaAdapter(config);
        }
        if (providerType == ProviderType.WENXIN) {
            throw new UnsupportedOperationException("WENXIN 适配器尚未实现");
        }
        if (providerType == ProviderType.ANTHROPIC) {
            return createAnthropicAdapter(config);
        }
        return createOpenAiCompatibleAdapter(config);
    }

    private SpringAiProviderAdapter createOllamaAdapter(ProviderConfig config) {
        var ollamaApi = OllamaApi.builder()
                .baseUrl(config.apiUrl())
                .build();

        var chatOptions = org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                .model(config.modelName())
                .build();

        ChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(chatOptions)
                .build();

        EmbeddingModel embeddingModel = null;
        if (config.hasCapability(ProviderCapability.EMBEDDING)) {
            var embeddingOptions = org.springframework.ai.ollama.api.OllamaEmbeddingOptions.builder()
                    .model(config.modelName())
                    .build();
            embeddingModel = OllamaEmbeddingModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(embeddingOptions)
                    .build();
        }

        log.info("创建 Ollama 适配器: id={}, model={}", config.id(), config.modelName());
        return new SpringAiProviderAdapter(config, chatModel, embeddingModel, defaultAdvisors);
    }

    private SpringAiProviderAdapter createAnthropicAdapter(ProviderConfig config) {
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Anthropic Provider 必须配置 API Key: id=" + config.id());
        }

        var anthropicApiBuilder = AnthropicApi.builder()
                .apiKey(apiKey)
                .baseUrl(config.apiUrl());

        // HTTP 超时配置（复用 OpenAI 兼容适配器的模式）
        if (connectionPoolConfig != null) {
            int httpTimeoutSeconds = Math.max(config.timeoutSeconds(), MIN_HTTP_TIMEOUT_SECONDS);
            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(httpTimeoutSeconds))
                    .build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(httpTimeoutSeconds));
            var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
            anthropicApiBuilder.restClientBuilder(restClientBuilder);
        }

        var anthropicApi = anthropicApiBuilder.build();

        var chatOptions = AnthropicChatOptions.builder()
                .model(config.modelName())
                .build();

        ChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(chatOptions)
                .build();

        // Anthropic 不提供 Embedding API，embeddingModel 始终为 null
        log.info("创建 Anthropic 原生适配器: id={}, model={}", config.id(), config.modelName());
        return new SpringAiProviderAdapter(config, chatModel, null, defaultAdvisors);
    }

    private SpringAiProviderAdapter createOpenAiCompatibleAdapter(ProviderConfig config) {
        var openAiApiBuilder = OpenAiApi.builder()
                .baseUrl(config.apiUrl());

        String apiKey = config.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            openAiApiBuilder.apiKey(apiKey);
        }

        if (connectionPoolConfig != null) {
            int httpTimeoutSeconds = Math.max(config.timeoutSeconds(), MIN_HTTP_TIMEOUT_SECONDS);
            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(httpTimeoutSeconds))
                    .build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(httpTimeoutSeconds));
            var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
            openAiApiBuilder.restClientBuilder(restClientBuilder);
            log.debug("云端 Provider HTTP 连接池配置: id={}, httpTimeout={}s, logicalTimeout={}s",
                    config.id(), httpTimeoutSeconds, config.timeoutSeconds());
        }

        var openAiApi = openAiApiBuilder.build();

        var chatOptions = OpenAiChatOptions.builder()
                .model(config.modelName())
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();

        EmbeddingModel embeddingModel = null;
        if (config.hasCapability(ProviderCapability.EMBEDDING)) {
            embeddingModel = new OpenAiEmbeddingModel(openAiApi);
        }

        log.info("创建 OpenAI 兼容适配器: id={}, type={}, model={}, hasEmbedding={}",
                config.id(), config.type(), config.modelName(), embeddingModel != null);
        return new SpringAiProviderAdapter(config, chatModel, embeddingModel, defaultAdvisors);
    }
}
