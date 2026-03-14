package com.lifepilot.llm;

import com.lifepilot.llm.cache.CacheEntry;
import com.lifepilot.llm.cache.SemanticCache;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * LLM 路由策略引擎。
 *
 * <p>核心路由流程：场景匹配 → 能力过滤 → 熔断器过滤 → 优先级排序 → 故障转移循环。
 * 成功时调用 recordSuccess 并返回 {@link LlmResponse}，
 * 所有候选失败时抛出 {@link LlmUnavailableException}。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final ProviderRegistry providerRegistry;
    private final CircuitBreakerManager circuitBreakerManager;
    private final ExponentialBackoff backoff;
    @Nullable private final LlmRoutingPreferenceResolver routingPreferenceResolver;
    @Nullable private volatile SemanticCache semanticCache;

    public LlmRouter(ProviderRegistry providerRegistry,
                     CircuitBreakerManager circuitBreakerManager,
                     @Nullable LlmRoutingPreferenceResolver routingPreferenceResolver) {
        this.providerRegistry = providerRegistry;
        this.circuitBreakerManager = circuitBreakerManager;
        this.routingPreferenceResolver = routingPreferenceResolver;
        this.backoff = ExponentialBackoff.defaults();
        log.info("LlmRouter 初始化完成");
    }

    /**
     * 注入语义缓存（延迟注入，避免循环依赖）。
     *
     * @param semanticCache 语义缓存实例
     */
    public void setSemanticCache(@Nullable SemanticCache semanticCache) {
        this.semanticCache = semanticCache;
    }

    /**
     * 执行文本生成调用，按优先级故障转移。
     *
     * @param scene        场景名称
     * @param prompt       提示词
     * @param outputSchema 输出 Schema（可选）
     * @return 统一响应
     * @throws LlmUnavailableException 所有候选 Provider 均失败
     */
    public LlmResponse call(String scene, String prompt, @Nullable String outputSchema) {
        return callWithPreferredProvider(scene, prompt, outputSchema, null, null);
    }

    public LlmResponse call(String scene,
                            ProviderCapability requiredCapability,
                            String prompt,
                            @Nullable String outputSchema,
                            @Nullable String modelName,
                            @Nullable String preferredProviderId) {
        return call(scene, requiredCapability, prompt, outputSchema, modelName, preferredProviderId, null);
    }

    public LlmResponse call(String scene,
                            ProviderCapability requiredCapability,
                            String prompt,
                            @Nullable String outputSchema,
                            @Nullable String modelName,
                            @Nullable String preferredProviderId,
                            @Nullable Duration timeoutOverride) {
        if (requiredCapability == ProviderCapability.CHAT) {
            if (modelName != null && !modelName.isBlank()) {
                return call(scene, prompt, outputSchema, modelName, timeoutOverride);
            }
            return callWithPreferredProvider(scene, prompt, outputSchema, preferredProviderId, timeoutOverride);
        }
        return callWithCapability(scene, requiredCapability, prompt, outputSchema, modelName, preferredProviderId, timeoutOverride);
    }

    public LlmResponse callWithPreferredProvider(String scene, String prompt,
                                                 @Nullable String outputSchema,
                                                 @Nullable String preferredProviderId) {
        return callWithPreferredProvider(scene, prompt, outputSchema, preferredProviderId, null);
    }

    public LlmResponse callWithPreferredProvider(String scene, String prompt,
                                                 @Nullable String outputSchema,
                                                 @Nullable String preferredProviderId,
                                                 @Nullable Duration timeoutOverride) {
        String normalizedScene = normalizeScene(scene);
        String responseFormatKey = responseFormatKey(outputSchema);
        // 缓存查询（在 Provider 故障转移循环前）
        if (semanticCache != null) {
            try {
                var cached = semanticCache.lookup(normalizedScene, null, responseFormatKey, prompt);
                if (cached.isPresent()) {
                    CacheEntry entry = cached.get();
                    log.debug("LLM 缓存命中: scene={}, format={}, cacheId={}",
                            scene, responseFormatKey, entry.id());
                    return LlmResponse.cached(entry.responseText(), "cache", entry.modelName());
                }
            } catch (Exception e) {
                log.warn("LLM 缓存查询异常，跳过缓存: scene={}, error={}", scene, e.getMessage());
            }
        }

        var candidates = findAvailableCandidates(normalizedScene, ProviderCapability.CHAT, preferredProviderId);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 Provider: scene=" + scene, scene, List.of());
        }

        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;

        for (int i = 0; i < candidates.size(); i++) {
            var config = candidates.get(i);
            attemptedProviders.add(config.id());

            try {
                var adapter = providerRegistry.getAdapter(config.id());
                var timeout = effectiveTimeout(config, timeoutOverride);
                
                // 记录提示词（用于调试）
                log.debug("LLM 调用开始: scene={}, provider={}, prompt={}", 
                        scene, config.id(), 
                        prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
                
                var response = adapter.call(prompt, outputSchema, timeout);
                circuitBreakerManager.recordSuccess(config.id(), "CHAT");
                
                // 记录回复（用于调试）
                log.info("LLM 调用成功: scene={}, provider={}, model={}, latency={}ms, tokens={}/{}",
                        scene, config.id(), response.modelName(), response.latencyMs(),
                        response.inputTokens(), response.outputTokens());
                log.debug("LLM 完整回复: content={}", 
                        response.content().length() > 500 ? response.content().substring(0, 500) + "..." : response.content());
                
                // 异步写入缓存
                if (semanticCache != null) {
                    try {
                        semanticCache.putAsync(
                                normalizedScene, null, responseFormatKey,
                                prompt, response.content(), response.modelName());
                    } catch (Exception e) {
                        log.warn("LLM 缓存写入异常，静默跳过: scene={}, error={}", scene, e.getMessage());
                    }
                }

                return response;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "CHAT");
                lastException = e;
                log.warn("LLM 调用失败: scene={}, provider={}, error={}",
                        scene, config.id(), e.getMessage());

                // 最后一个候选失败后不等待
                if (i < candidates.size() - 1) {
                    sleepBackoff(i);
                }
            }
        }

        throw new LlmUnavailableException(
                "所有候选 Provider 调用失败: scene=" + scene,
                scene, attemptedProviders, lastException);
    }

    /**
     * 执行文本生成调用，优先按模型名路由，未找到时回退到 scene-based 路由。
     *
     * @param scene        场景名称
     * @param prompt       提示词
     * @param outputSchema 输出 Schema（可选）
     * @param modelName    指定模型名（可选，null 时回退到 scene-based 路由）
     * @return 统一响应
     * @throws LlmUnavailableException 所有候选 Provider 均失败
     */
    public LlmResponse call(String scene, String prompt, @Nullable String outputSchema,
                            @Nullable String modelName) {
        return call(scene, prompt, outputSchema, modelName, null);
    }

    public LlmResponse call(String scene, String prompt, @Nullable String outputSchema,
                            @Nullable String modelName,
                            @Nullable Duration timeoutOverride) {
        if (modelName == null || modelName.isBlank()) {
            return callWithPreferredProvider(scene, prompt, outputSchema, null, timeoutOverride);
        }
        var byModel = providerRegistry.findByModelName(modelName).stream()
                .filter(c -> c.hasCapability(ProviderCapability.CHAT))
                .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), "CHAT"))
                .toList();
        if (byModel.isEmpty()) {
            log.warn("指定 modelName 未找到可用 Provider，回退到 scene 路由: modelName={}, scene={}",
                    modelName, scene);
            return callWithPreferredProvider(scene, prompt, outputSchema, null, timeoutOverride);
        }
        return callWithCandidates(scene, prompt, outputSchema, byModel, timeoutOverride);
    }

    /**
     * 使用指定候选列表执行文本生成调用（内部复用方法）。
     */
    private LlmResponse callWithCapability(String scene,
                                           ProviderCapability requiredCapability,
                                           String prompt,
                                           @Nullable String outputSchema,
                                           @Nullable String modelName,
                                           @Nullable String preferredProviderId,
                                           @Nullable Duration timeoutOverride) {
        String normalizedScene = normalizeScene(scene);
        String responseFormatKey = responseFormatKey(outputSchema);
        boolean cacheable = requiredCapability == ProviderCapability.STRUCTURED_OUTPUT;

        if (cacheable && semanticCache != null) {
            try {
                var cached = semanticCache.lookup(normalizedScene, null, responseFormatKey, prompt);
                if (cached.isPresent()) {
                    CacheEntry entry = cached.get();
                    log.debug("LLM 缓存命中: scene={}, capability={}, format={}, cacheId={}",
                            scene, requiredCapability, responseFormatKey, entry.id());
                    return LlmResponse.cached(entry.responseText(), "cache", entry.modelName());
                }
            } catch (Exception e) {
                log.warn("LLM 缓存查询异常，跳过缓存: scene={}, capability={}, error={}",
                        scene, requiredCapability, e.getMessage());
            }
        }

        var candidates = (modelName != null && !modelName.isBlank()
                ? providerRegistry.findByModelName(modelName).stream()
                    .filter(c -> c.hasCapability(requiredCapability))
                    .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), requiredCapability.name()))
                    .toList()
                : findAvailableCandidates(normalizedScene, requiredCapability, preferredProviderId));

        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 Provider: scene=" + scene, scene, List.of());
        }

        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;
        for (int i = 0; i < candidates.size(); i++) {
            var config = candidates.get(i);
            attemptedProviders.add(config.id());
            try {
                var adapter = providerRegistry.getAdapter(config.id());
                var timeout = effectiveTimeout(config, timeoutOverride);
                var response = adapter.call(prompt, outputSchema, timeout);
                circuitBreakerManager.recordSuccess(config.id(), requiredCapability.name());
                if (cacheable && semanticCache != null) {
                    try {
                        semanticCache.putAsync(
                                normalizedScene, null, responseFormatKey,
                                prompt, response.content(), response.modelName());
                    } catch (Exception e) {
                        log.warn("LLM 缓存写入异常，静默跳过: scene={}, capability={}, error={}",
                                scene, requiredCapability, e.getMessage());
                    }
                }
                return response;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), requiredCapability.name());
                lastException = e;
                log.warn("LLM 调用失败: scene={}, capability={}, provider={}, error={}",
                        scene, requiredCapability, config.id(), e.getMessage());
                if (i < candidates.size() - 1) {
                    sleepBackoff(i);
                }
            }
        }
        throw new LlmUnavailableException(
                "所有候选 Provider 调用失败: scene=" + scene, scene, attemptedProviders, lastException);
    }

    private LlmResponse callWithCandidates(String scene, String prompt,
                                            @Nullable String outputSchema,
                                            List<ProviderConfig> candidates,
                                            @Nullable Duration timeoutOverride) {
        String normalizedScene = normalizeScene(scene);
        String responseFormatKey = responseFormatKey(outputSchema);
        if (semanticCache != null) {
            try {
                var cached = semanticCache.lookup(normalizedScene, null, responseFormatKey, prompt);
                if (cached.isPresent()) {
                    CacheEntry entry = cached.get();
                    log.debug("LLM 缓存命中: scene={}, format={}, cacheId={}",
                            scene, responseFormatKey, entry.id());
                    return LlmResponse.cached(entry.responseText(), "cache", entry.modelName());
                }
            } catch (Exception e) {
                log.warn("LLM 缓存查询异常，跳过缓存: scene={}, error={}", scene, e.getMessage());
            }
        }
        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;
        for (int i = 0; i < candidates.size(); i++) {
            var config = candidates.get(i);
            attemptedProviders.add(config.id());
            try {
                var adapter = providerRegistry.getAdapter(config.id());
                var timeout = effectiveTimeout(config, timeoutOverride);
                var response = adapter.call(prompt, outputSchema, timeout);
                circuitBreakerManager.recordSuccess(config.id(), "CHAT");
                log.info("LLM 调用成功（模型路由）: scene={}, provider={}, model={}",
                        scene, config.id(), response.modelName());
                if (semanticCache != null) {
                    try {
                        semanticCache.putAsync(
                                normalizedScene, null, responseFormatKey,
                                prompt, response.content(), response.modelName());
                    } catch (Exception e) {
                        log.warn("LLM 缓存写入异常: scene={}, error={}", scene, e.getMessage());
                    }
                }
                return response;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "CHAT");
                lastException = e;
                log.warn("LLM 调用失败: scene={}, provider={}, error={}", scene, config.id(), e.getMessage());
                if (i < candidates.size() - 1) sleepBackoff(i);
            }
        }
        throw new LlmUnavailableException(
                "所有候选 Provider 调用失败: scene=" + scene, scene, attemptedProviders, lastException);
    }

    /**
     * 执行结构化输出调用，优先选择支持 STRUCTURED_OUTPUT 的 Provider。
     *
     * @param scene        场景名称
     * @param prompt       提示词
     * @param responseType 响应类型
     * @param <T>          响应泛型
     * @return 结构化响应对象
     * @throws LlmUnavailableException 所有候选 Provider 均失败
     */
    public <T> T callEntity(String scene, String prompt, Class<T> responseType) {
        String normalizedScene = normalizeScene(scene);
        // 优先选择支持 STRUCTURED_OUTPUT 的 Provider
        var candidates = findAvailableCandidates(normalizedScene, ProviderCapability.CHAT);
        // 将支持 STRUCTURED_OUTPUT 的排在前面
        var sorted = new ArrayList<>(candidates.stream()
                .filter(c -> c.hasCapability(ProviderCapability.STRUCTURED_OUTPUT))
                .toList());
        candidates.stream()
                .filter(c -> !c.hasCapability(ProviderCapability.STRUCTURED_OUTPUT))
                .forEach(sorted::add);

        if (sorted.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 Provider: scene=" + scene, scene, List.of());
        }

        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;

        for (int i = 0; i < sorted.size(); i++) {
            var config = sorted.get(i);
            attemptedProviders.add(config.id());

            try {
                var adapter = providerRegistry.getAdapter(config.id());
                T result = adapter.callEntity(prompt, responseType);
                circuitBreakerManager.recordSuccess(config.id(), "CHAT");
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "CHAT");
                lastException = e;
                log.warn("LLM callEntity 失败: scene={}, provider={}, error={}",
                        scene, config.id(), e.getMessage());
                if (i < sorted.size() - 1) {
                    sleepBackoff(i);
                }
            }
        }

        throw new LlmUnavailableException(
                "所有候选 Provider callEntity 失败: scene=" + scene,
                scene, attemptedProviders, lastException);
    }

    /**
     * 执行结构化输出调用，优先按模型名路由，未找到时回退到 scene-based 路由。
     *
     * @param scene        场景名称
     * @param prompt       提示词
     * @param responseType 响应类型
     * @param modelName    指定模型名（可选，null 时回退到 scene-based 路由）
     * @param <T>          响应泛型
     * @return 结构化响应对象
     * @throws LlmUnavailableException 所有候选 Provider 均失败
     */
    public <T> T callEntity(String scene, String prompt, Class<T> responseType,
                            @Nullable String modelName) {
        String normalizedScene = normalizeScene(scene);
        if (modelName == null || modelName.isBlank()) {
            return callEntity(normalizedScene, prompt, responseType);
        }
        var byModel = providerRegistry.findByModelName(modelName).stream()
                .filter(c -> c.hasCapability(ProviderCapability.CHAT))
                .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), "CHAT"))
                .toList();
        if (byModel.isEmpty()) {
            log.warn("指定 modelName 未找到可用 Provider，回退到 scene 路由: modelName={}, scene={}",
                    modelName, scene);
            return callEntity(normalizedScene, prompt, responseType);
        }
        // 将支持 STRUCTURED_OUTPUT 的排在前面
        var sorted = new ArrayList<>(byModel.stream()
                .filter(c -> c.hasCapability(ProviderCapability.STRUCTURED_OUTPUT))
                .toList());
        byModel.stream()
                .filter(c -> !c.hasCapability(ProviderCapability.STRUCTURED_OUTPUT))
                .forEach(sorted::add);

        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;
        for (int i = 0; i < sorted.size(); i++) {
            var config = sorted.get(i);
            attemptedProviders.add(config.id());
            try {
                var adapter = providerRegistry.getAdapter(config.id());
                T result = adapter.callEntity(prompt, responseType);
                circuitBreakerManager.recordSuccess(config.id(), "CHAT");
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "CHAT");
                lastException = e;
                log.warn("LLM callEntity 失败（模型路由）: scene={}, provider={}, error={}",
                        scene, config.id(), e.getMessage());
                if (i < sorted.size() - 1) sleepBackoff(i);
            }
        }
        throw new LlmUnavailableException(
                "所有候选 Provider callEntity 失败: scene=" + scene,
                scene, attemptedProviders, lastException);
    }

    /**
     * 获取最高优先级可用 Provider 的 ChatClient。
     *
     * @param scene 场景名称
     * @return ChatClient 实例
     * @throws LlmUnavailableException 无可用 Provider
     */
    public ChatClient getChatClient(String scene) {
        return getChatClient(scene, null);
    }

    /**
     * 获取最高优先级可用 Provider 的 ChatClient，可显式指定优先 Provider。
     */
    public ChatClient getChatClient(String scene, @Nullable String preferredProviderId) {
        String normalizedScene = normalizeScene(scene);
        var candidates = findAvailableCandidates(normalizedScene, ProviderCapability.CHAT, preferredProviderId);
        for (var config : candidates) {
            var adapter = providerRegistry.getAdapter(config.id());
            var client = adapter.chatClient();
            if (client.isPresent()) {
                return client.get();
            }
        }
        throw new LlmUnavailableException(
                "无可用 ChatClient: scene=" + scene, scene, List.of());
    }

    /**
     * ChatClient 附带 Provider 元信息（用于可观测性 / trace 记录）。
     *
     * @param client     ChatClient 实例
     * @param providerId Provider ID
     * @param modelId    模型 ID
     */
    public record ChatClientInfo(ChatClient client, String providerId, String modelId) {}

    /**
     * 获取支持流式的最高优先级 Provider 的 ChatClient 及元信息。
     *
     * @param scene 场景名称
     * @return ChatClient + providerId + modelId
     * @throws LlmUnavailableException 无可用流式 Provider
     */
    public ChatClientInfo getChatClientWithInfo(String scene) {
        return getChatClientWithInfo(scene, null);
    }

    /**
     * 获取支持流式的最高优先级 Provider 的 ChatClient 及元信息，可显式指定优先 Provider。
     */
    public ChatClientInfo getChatClientWithInfo(String scene, @Nullable String preferredProviderId) {
        String normalizedScene = normalizeScene(scene);
        var candidates = findAvailableCandidates(normalizedScene, ProviderCapability.CHAT, preferredProviderId)
                .stream()
                .filter(ProviderConfig::supportsStreaming)
                .toList();
        for (var config : candidates) {
            var adapter = providerRegistry.getAdapter(config.id());
            var client = adapter.chatClient();
            if (client.isPresent()) {
                return new ChatClientInfo(client.get(), config.id(), config.modelName());
            }
        }
        throw new LlmUnavailableException(
                "无可用流式 ChatClient: scene=" + scene, scene, List.of());
    }

    /**
     * ChatModel 附带 Provider 元信息（用于手动 tool calling 场景）。
     *
     * @param chatModel  ChatModel 实例
     * @param providerId Provider ID
     * @param modelId    模型 ID
     */
    public record ChatModelInfo(org.springframework.ai.chat.model.ChatModel chatModel,
                                String providerId, String modelId) {}

    /**
     * 获取最高优先级 Provider 的 ChatModel 及元信息。
     *
     * <p>用于需要手动控制 tool calling 的场景（如 ReAct 循环），
     * 调用方可通过 {@code ChatModel.call(Prompt)} 获取原始响应，
     * 自行解析 tool call 请求并执行。</p>
     *
     * @param scene               场景名称
     * @param preferredProviderId 优先 Provider ID（可为 null）
     * @return ChatModel + providerId + modelId
     * @throws LlmUnavailableException 无可用 Provider
     */
    public ChatModelInfo getChatModelWithInfo(String scene, @Nullable String preferredProviderId) {
        String normalizedScene = normalizeScene(scene);
        var candidates = findAvailableCandidates(normalizedScene, ProviderCapability.CHAT, preferredProviderId);
        for (var config : candidates) {
            var adapter = providerRegistry.getAdapter(config.id());
            if (adapter instanceof com.lifepilot.llm.adapter.SpringAiProviderAdapter springAdapter) {
                return new ChatModelInfo(springAdapter.chatModel(), config.id(), config.modelName());
            }
        }
        throw new LlmUnavailableException(
                "无可用 ChatModel: scene=" + scene, scene, List.of());
    }

    /**
     * 执行文本嵌入，使用独立 EMBEDDING 熔断器隔离。
     *
     * @param text 待嵌入文本
     * @return 嵌入向量
     * @throws LlmUnavailableException 所有 EMBEDDING Provider 均失败
     */
    public float[] embed(String text) {
        var candidates = providerRegistry.findByCapability(ProviderCapability.EMBEDDING)
                .stream()
                .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), "EMBEDDING"))
                .toList();

        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 EMBEDDING Provider", LlmScene.EMBEDDING, List.of());
        }

        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;

        for (int i = 0; i < candidates.size(); i++) {
            var config = candidates.get(i);
            attemptedProviders.add(config.id());

            try {
                var adapter = providerRegistry.getAdapter(config.id());
                float[] result = adapter.embed(text);
                circuitBreakerManager.recordSuccess(config.id(), "EMBEDDING");
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "EMBEDDING");
                lastException = e;
                log.warn("Embedding 调用失败: provider={}, error={}",
                        config.id(), e.getMessage());
                if (i < candidates.size() - 1) {
                    sleepBackoff(i);
                }
            }
        }

        throw new LlmUnavailableException(
                "所有 EMBEDDING Provider 调用失败",
                LlmScene.EMBEDDING, attemptedProviders, lastException);
    }

    /**
     * 执行文本嵌入，优先按模型名路由，未找到时回退到默认 EMBEDDING Provider。
     *
     * @param text      待嵌入文本
     * @param modelName 指定模型名（可选，null 时回退到默认 EMBEDDING Provider）
     * @return 嵌入向量
     * @throws LlmUnavailableException 所有 EMBEDDING Provider 均失败
     */
    public float[] embed(String text, @Nullable String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return embed(text);
        }
        var byModel = providerRegistry.findByModelName(modelName).stream()
                .filter(c -> c.hasCapability(ProviderCapability.EMBEDDING))
                .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), "EMBEDDING"))
                .toList();
        if (byModel.isEmpty()) {
            log.warn("指定 embeddingModel 未找到可用 Provider，回退到默认: modelName={}", modelName);
            return embed(text);
        }
        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;
        for (int i = 0; i < byModel.size(); i++) {
            var config = byModel.get(i);
            attemptedProviders.add(config.id());
            try {
                var adapter = providerRegistry.getAdapter(config.id());
                float[] result = adapter.embed(text);
                circuitBreakerManager.recordSuccess(config.id(), "EMBEDDING");
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "EMBEDDING");
                lastException = e;
                log.warn("Embedding 调用失败（模型路由）: provider={}, error={}", config.id(), e.getMessage());
                if (i < byModel.size() - 1) sleepBackoff(i);
            }
        }
        throw new LlmUnavailableException(
                "所有 EMBEDDING Provider 调用失败（模型路由）",
                LlmScene.EMBEDDING, attemptedProviders, lastException);
    }

    /**
     * 执行流式文本生成，选择最高优先级可用 Provider，不执行中途故障转移。
     *
     * @param scene  场景名称
     * @param prompt 提示词
     * @return 流式文本响应
     * @throws LlmUnavailableException 无可用 STREAMING Provider
     */
    public Flux<String> stream(String scene, String prompt) {
        return streamWithInfo(scene, prompt).stream();
    }

    /**
     * 执行流式文本生成，并返回附带 Provider/模型信息的响应包装。
     *
     * <p>用于 SSE/流式通道补齐可观测性数据（例如 modelId）。</p>
     *
     * @param scene  场景名称
     * @param prompt 提示词
     * @return 流式响应（含 providerId/modelId）
     * @throws LlmUnavailableException 无可用 STREAMING Provider
     */
    public StreamingLlmResponse streamWithInfo(String scene, String prompt) {
        return streamWithInfo(scene, prompt, null);
    }

    /**
     * 执行流式文本生成，并返回附带 Provider/模型信息的响应包装，可显式指定优先 Provider。
     */
    public StreamingLlmResponse streamWithInfo(String scene, String prompt,
                                               @Nullable String preferredProviderId) {
        String normalizedScene = normalizeScene(scene);
        var candidates = findAvailableCandidates(normalizedScene, ProviderCapability.CHAT, preferredProviderId)
                .stream()
                .filter(ProviderConfig::supportsStreaming)
                .toList();

        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 STREAMING Provider: scene=" + scene, scene, List.of());
        }

        // 选择最高优先级（第一个），不执行中途故障转移
        var config = candidates.getFirst();
        var adapter = providerRegistry.getAdapter(config.id());
        log.debug("流式调用: scene={}, provider={}", scene, config.id());
        return new StreamingLlmResponse(adapter.stream(prompt), config.id(), config.modelName());
    }

    public List<ProviderConfig> getAvailableCandidates(String scene,
                                                       ProviderCapability requiredCapability,
                                                       @Nullable String preferredProviderId) {
        return findAvailableCandidates(normalizeScene(scene), requiredCapability, preferredProviderId);
    }

    // --- 内部方法 ---

    /**
     * 查找可用候选 Provider：场景匹配 → 能力过滤 → 熔断器过滤。
     * 场景无匹配时回退到按能力查找，确保不会因 scene 缺失而直接抛异常。
     */
    private List<ProviderConfig> findAvailableCandidates(String scene,
                                                          ProviderCapability requiredCapability) {
        return findAvailableCandidates(scene, requiredCapability, null);
    }

    private List<ProviderConfig> findAvailableCandidates(String scene,
                                                          ProviderCapability requiredCapability,
                                                          @Nullable String explicitPreferredProviderId) {
        String preferredProviderId = resolvePreferredProvider(scene, explicitPreferredProviderId);
        var byScene = providerRegistry.findByScene(scene);
        log.debug("场景匹配结果: scene={}, 匹配数量={}, providers={}",
                scene, byScene.size(),
                byScene.stream().map(ProviderConfig::id).toList());
        
        List<ProviderConfig> candidates = byScene.stream()
                .filter(c -> c.hasCapability(requiredCapability))
                .filter(c -> circuitBreakerManager.isCallPermitted(
                        c.id(), requiredCapability.name()))
                .toList();
        candidates = prioritizePreferredProvider(
                candidates, preferredProviderId, requiredCapability, scene, false);
        
        // 场景无匹配时回退到按能力查找
        if (candidates.isEmpty()) {
            List<ProviderConfig> fallback = providerRegistry.findByCapability(requiredCapability).stream()
                    .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), requiredCapability.name()))
                    .toList();
            fallback = prioritizePreferredProvider(
                    fallback, preferredProviderId, requiredCapability, scene, true);
            if (!fallback.isEmpty()) {
                log.info("场景 '{}' 无匹配 Provider，回退到能力路由: capability={}, 候选数={}",
                        scene, requiredCapability, fallback.size());
                return fallback;
            }
            log.warn("场景 '{}' 无匹配 Provider，能力回退也无可用候选: capability={}", scene, requiredCapability);
        }
        
        return candidates;
    }

    @Nullable
    private String resolvePreferredProvider(String scene, @Nullable String explicitPreferredProviderId) {
        if (explicitPreferredProviderId != null && !explicitPreferredProviderId.isBlank()) {
            return explicitPreferredProviderId;
        }
        if (routingPreferenceResolver == null) {
            return null;
        }
        return routingPreferenceResolver.preferredProviderForScene(normalizeScene(scene));
    }

    private List<ProviderConfig> prioritizePreferredProvider(List<ProviderConfig> candidates,
                                                             @Nullable String preferredProviderId,
                                                             ProviderCapability requiredCapability,
                                                             String scene,
                                                             boolean allowCapabilityFallback) {
        if (preferredProviderId == null || preferredProviderId.isBlank()) {
            return candidates;
        }

        var preferredConfig = providerRegistry.getConfig(preferredProviderId)
                .filter(config -> config.hasCapability(requiredCapability))
                .filter(config -> circuitBreakerManager.isCallPermitted(
                        config.id(), requiredCapability.name()))
                .filter(config -> allowCapabilityFallback || config.supportsScene(scene));

        if (preferredConfig.isEmpty()) {
            return candidates;
        }

        var ordered = new ArrayList<ProviderConfig>();
        ordered.add(preferredConfig.get());
        candidates.stream()
                .filter(config -> !config.id().equals(preferredProviderId))
                .forEach(ordered::add);
        return ordered;
    }

    /**
     * 指数退避等待。
     */
    private void sleepBackoff(int attempt) {
        long delay = backoff.delayForAttempt(attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Duration effectiveTimeout(ProviderConfig config, @Nullable Duration timeoutOverride) {
        return timeoutOverride != null ? timeoutOverride : Duration.ofSeconds(config.timeoutSeconds());
    }

    private String responseFormatKey(@Nullable String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return "text";
        }
        return "json:" + sha256Hex(outputSchema.trim());
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String normalizeScene(String scene) {
        return scene == null ? "" : scene.trim();
    }
}
