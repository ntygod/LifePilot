package com.lifepilot.llm.adapter;

import com.lifepilot.llm.cache.PromptCacheStrategies;
import com.lifepilot.llm.cache.PromptCacheStrategy;
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
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

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

        // Anthropic API 的 baseUrl 不应包含 /v1 后缀（AnthropicApi 会自动拼接 /v1/messages）
        // 用户可能习惯性填写 https://example.com/v1（OpenAI 兼容格式），这里自动修正
        String baseUrl = config.apiUrl();
        if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) {
            baseUrl = baseUrl.replaceAll("/v1/?$", "");
            log.info("Anthropic baseUrl 自动修正: 移除 /v1 后缀, id={}, 修正后={}", config.id(), baseUrl);
        }

        var anthropicApiBuilder = AnthropicApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl);

        // Prompt 缓存策略 — Anthropic 走显式 cache_control ephemeral 注入
        PromptCacheStrategy cacheStrategy = PromptCacheStrategies.resolve(config);
        applyCacheStrategyToAnthropic(anthropicApiBuilder, cacheStrategy, config);

        var anthropicApi = anthropicApiBuilder.build();

        var chatOptions = AnthropicChatOptions.builder()
                .model(config.modelName())
                .build();

        ChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(chatOptions)
                .build();

        // Anthropic 不提供 Embedding API，embeddingModel 始终为 null
        log.info("创建 Anthropic 原生适配器: id={}, model={}, cacheStrategy={}",
                config.id(), config.modelName(), cacheStrategy.name());
        return new SpringAiProviderAdapter(config, chatModel, null, defaultAdvisors);
    }

    private SpringAiProviderAdapter createOpenAiCompatibleAdapter(ProviderConfig config) {
        String baseUrl = normalizeOpenAiCompatibleBaseUrl(config.apiUrl(), config.id());
        var openAiApiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl);

        String apiKey = config.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            openAiApiBuilder.apiKey(apiKey);
        }

        // Prompt 缓存策略 — DashScope 需要显式 cache_control 注入; OpenAI 官方 / DeepSeek 等
        // provider 侧自动缓存, 走 noop pass-through; 策略自行决定两端拦截点是否生效。
        PromptCacheStrategy cacheStrategy = PromptCacheStrategies.resolve(config);
        applyCacheStrategyToOpenAi(openAiApiBuilder, cacheStrategy, config);

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

        log.info("创建 OpenAI 兼容适配器: id={}, type={}, model={}, hasEmbedding={}, cacheStrategy={}",
                config.id(), config.type(), config.modelName(), embeddingModel != null,
                cacheStrategy.name());
        return new SpringAiProviderAdapter(config, chatModel, embeddingModel, defaultAdvisors);
    }

    /**
     * 把 prompt 缓存策略的拦截器 / filter 挂到 {@link OpenAiApi.Builder} 上。
     *
     * <p>装配顺序: 连接池 timeout 配置 → 缓存 RestClient 拦截器 → 挂 restClientBuilder →
     * 缓存 WebClient filter (若策略提供) → 挂 webClientBuilder。与 Anthropic 分支对称。</p>
     *
     * @param apiBuilder    OpenAI 兼容 API builder
     * @param strategy      已解析的缓存策略 (永不为 null; noop 策略两端均返回 null, 走 pass-through)
     * @param config        Provider 配置 (读取 timeoutSeconds / id)
     */
    private void applyCacheStrategyToOpenAi(OpenAiApi.Builder apiBuilder,
                                            PromptCacheStrategy strategy,
                                            ProviderConfig config) {
        ClientHttpRequestInterceptor restInterceptor = strategy.restClientInterceptor();
        ExchangeFilterFunction webFilter = strategy.webClientFilter();

        RestClient.Builder restClientBuilder = buildRestClientBuilder(config, true);
        if (restInterceptor != null) {
            restClientBuilder.requestInterceptor(restInterceptor);
        }
        apiBuilder.restClientBuilder(restClientBuilder);

        if (webFilter != null) {
            WebClient.Builder webClientBuilder = WebClient.builder().filter(webFilter);
            apiBuilder.webClientBuilder(webClientBuilder);
        }
    }

    /**
     * 把 prompt 缓存策略的拦截器 / filter 挂到 {@link AnthropicApi.Builder} 上。
     *
     * <p>装配顺序与 {@link #applyCacheStrategyToOpenAi} 完全一致, 仅目标 builder 类型不同。</p>
     *
     * @param apiBuilder    Anthropic API builder
     * @param strategy      已解析的缓存策略
     * @param config        Provider 配置 (读取 timeoutSeconds / id)
     */
    private void applyCacheStrategyToAnthropic(AnthropicApi.Builder apiBuilder,
                                               PromptCacheStrategy strategy,
                                               ProviderConfig config) {
        ClientHttpRequestInterceptor restInterceptor = strategy.restClientInterceptor();
        ExchangeFilterFunction webFilter = strategy.webClientFilter();

        // Anthropic 分支历史未打连接池 DEBUG 日志, 保持行为一致: logPool=false
        RestClient.Builder restClientBuilder = buildRestClientBuilder(config, false);
        if (restInterceptor != null) {
            restClientBuilder.requestInterceptor(restInterceptor);
        }
        apiBuilder.restClientBuilder(restClientBuilder);

        if (webFilter != null) {
            WebClient.Builder webClientBuilder = WebClient.builder().filter(webFilter);
            apiBuilder.webClientBuilder(webClientBuilder);
        }
    }

    /**
     * 构建 RestClient.Builder, 合并连接池超时配置 (若 {@link #connectionPoolConfig} 非空)。
     *
     * @param config  Provider 配置 (用于读取 timeoutSeconds 和 id)
     * @param logPool 是否输出 "云端 Provider HTTP 连接池配置" DEBUG 日志 (仅 OpenAI 兼容分支历史有该日志)
     * @return 已配置 request factory 但尚未挂拦截器的 builder
     */
    private RestClient.Builder buildRestClientBuilder(ProviderConfig config, boolean logPool) {
        if (connectionPoolConfig == null) {
            return RestClient.builder();
        }
        int httpTimeoutSeconds = Math.max(config.timeoutSeconds(), MIN_HTTP_TIMEOUT_SECONDS);
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(httpTimeoutSeconds))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(httpTimeoutSeconds));
        if (logPool) {
            log.debug("云端 Provider HTTP 连接池配置: id={}, httpTimeout={}s, logicalTimeout={}s",
                    config.id(), httpTimeoutSeconds, config.timeoutSeconds());
        }
        return RestClient.builder().requestFactory(requestFactory);
    }

    static String normalizeOpenAiCompatibleBaseUrl(String baseUrl, String providerId) {
        if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) {
            String normalized = baseUrl.replaceAll("/v1/?$", "");
            log.info("OpenAI 兼容 baseUrl 自动修正: 移除 /v1 后缀, id={}, 修正后={}",
                    providerId, normalized);
            return normalized;
        }
        return baseUrl;
    }
}
