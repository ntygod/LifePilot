package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.LlmConfigProperties.ConnectionPoolConfigEntry;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>根据 {@link com.lifepilot.llm.config.ProviderType} 创建对应的
 * {@link SpringAiProviderAdapter}，手动构建 ChatModel/EmbeddingModel 实例。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ProviderAdapterFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderAdapterFactory.class);

    private final List<CallAdvisor> defaultAdvisors;
    @Nullable
    private final ConnectionPoolConfigEntry connectionPoolConfig;

    /**
     * 创建 Provider 适配器工厂。
     *
     * @param defaultAdvisors      默认 Advisor 列表（可选）
     * @param connectionPoolConfig HTTP 连接池配置（可选）
     */
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

    /**
     * 无参构造（用于测试）。
     */
    public ProviderAdapterFactory() {
        this.defaultAdvisors = List.of();
        this.connectionPoolConfig = null;
    }

    /**
     * 根据 Provider 配置创建适配器。
     *
     * @param config Provider 配置
     * @return 适配器实例
     */
    public SpringAiProviderAdapter create(ProviderConfig config) {
        return switch (config.type()) {
            case OLLAMA -> createOllamaAdapter(config);
            case DEEPSEEK, QWEN, GLM, TEI, OPENAI_COMPATIBLE -> createOpenAiCompatibleAdapter(config);
            case WENXIN -> throw new UnsupportedOperationException(
                    "WENXIN 适配器尚未实现，留给 llm-router-advanced");
        };
    }

    /**
     * 创建 Ollama 适配器。
     */
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

        // 仅在具备 EMBEDDING 能力时创建 EmbeddingModel
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

    /**
     * 创建 OpenAI 兼容适配器（DeepSeek / Qwen / GLM / TEI / OpenAI Compatible）。
     */
    private SpringAiProviderAdapter createOpenAiCompatibleAdapter(ProviderConfig config) {
        var openAiApiBuilder = OpenAiApi.builder()
                .baseUrl(config.apiUrl());

        // 避免潜在的 NPE，先缓存 apiKey
        String apiKey = config.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            openAiApiBuilder.apiKey(apiKey);
        }

        // 配置 HTTP 连接池参数（连接超时 + 保活）
        if (connectionPoolConfig != null) {
            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
            var restClientBuilder = RestClient.builder().requestFactory(requestFactory);
            openAiApiBuilder.restClientBuilder(restClientBuilder);
            log.debug("云端 Provider HTTP 连接池配置: id={}, connectTimeout={}s",
                    config.id(), config.timeoutSeconds());
        }

        var openAiApi = openAiApiBuilder.build();

        var chatOptions = OpenAiChatOptions.builder()
                .model(config.modelName())
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();

        // 仅在具备 EMBEDDING 能力时创建 EmbeddingModel
        EmbeddingModel embeddingModel = null;
        if (config.hasCapability(ProviderCapability.EMBEDDING)) {
            embeddingModel = new OpenAiEmbeddingModel(openAiApi);
        }

        log.info("创建 OpenAI 兼容适配器: id={}, type={}, model={}, hasEmbedding={}",
                config.id(), config.type(), config.modelName(),
                embeddingModel != null);
        return new SpringAiProviderAdapter(config, chatModel, embeddingModel, defaultAdvisors);
    }
}
