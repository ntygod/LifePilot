package com.lifepilot.llm;

import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
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

    public LlmRouter(ProviderRegistry providerRegistry,
                     CircuitBreakerManager circuitBreakerManager) {
        this.providerRegistry = providerRegistry;
        this.circuitBreakerManager = circuitBreakerManager;
        this.backoff = ExponentialBackoff.defaults();
        log.info("LlmRouter 初始化完成");
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
        var candidates = findAvailableCandidates(scene, ProviderCapability.CHAT);
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
                var timeout = Duration.ofSeconds(config.timeoutSeconds());
                
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
        // 优先选择支持 STRUCTURED_OUTPUT 的 Provider
        var candidates = findAvailableCandidates(scene, ProviderCapability.CHAT);
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
     * 获取最高优先级可用 Provider 的 ChatClient。
     *
     * @param scene 场景名称
     * @return ChatClient 实例
     * @throws LlmUnavailableException 无可用 Provider
     */
    public ChatClient getChatClient(String scene) {
        var candidates = findAvailableCandidates(scene, ProviderCapability.CHAT);
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
        var candidates = findAvailableCandidates(scene, ProviderCapability.CHAT)
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
        var candidates = findAvailableCandidates(scene, ProviderCapability.CHAT)
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

    // --- 内部方法 ---

    /**
     * 查找可用候选 Provider：场景匹配 → 能力过滤 → 熔断器过滤。
     */
    private List<ProviderConfig> findAvailableCandidates(String scene,
                                                          ProviderCapability requiredCapability) {
        var byScene = providerRegistry.findByScene(scene);
        log.debug("场景匹配结果: scene={}, 匹配数量={}, providers={}",
                scene, byScene.size(),
                byScene.stream().map(ProviderConfig::id).toList());
        
        var candidates = byScene.stream()
                .filter(c -> c.hasCapability(requiredCapability))
                .filter(c -> circuitBreakerManager.isCallPermitted(
                        c.id(), requiredCapability.name()))
                .toList();
        
        if (candidates.isEmpty() && !byScene.isEmpty()) {
            log.warn("场景匹配到 Provider 但无可用候选: scene={}, 匹配的Provider={}, 需要能力={}",
                    scene,
                    byScene.stream().map(c -> c.id() + "(能力:" + c.capabilities() + ")").toList(),
                    requiredCapability);
        } else if (candidates.isEmpty()) {
            log.warn("场景无匹配 Provider: scene={}, 已注册Provider={}, 各Provider场景={}",
                    scene,
                    providerRegistry.registeredIds(),
                    providerRegistry.registeredIds().stream()
                            .map(id -> {
                                var config = providerRegistry.getConfig(id);
                                return config.map(c -> id + ":" + c.scenes()).orElse(id + ":未找到");
                            })
                            .toList());
        }
        
        return candidates;
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
}
