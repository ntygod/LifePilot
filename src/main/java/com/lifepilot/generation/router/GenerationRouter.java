package com.lifepilot.generation.router;

import com.lifepilot.generation.client.GenerationClientFactory;
import com.lifepilot.generation.client.GenerationServiceClient;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.StreamingLlmResponse;
import com.lifepilot.llm.cache.SemanticCache;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.profile.BaseAdapterType;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import com.lifepilot.llm.stream.LlmStreamEvent;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 生成路由器。
 *
 * <p>负责聊天、结构化输出、流式生成和底层 ChatModel/ChatClient 选择。</p>
 *
 * @author zsg
 * @since 2026-03-24
 */
public class GenerationRouter {

    private static final Logger log = LoggerFactory.getLogger(GenerationRouter.class);

    private final ModelServiceRegistry registry;
    private final GenerationSettingsRepository settingsRepository;
    private final GenerationClientFactory clientFactory;
    private final CircuitBreakerManager circuitBreakerManager;
    private final ProviderProfileRegistry profileRegistry;
    @Nullable
    private volatile SemanticCache semanticCache;

    public GenerationRouter(ModelServiceRegistry registry,
                            GenerationSettingsRepository settingsRepository,
                            GenerationClientFactory clientFactory,
                            CircuitBreakerManager circuitBreakerManager,
                            ProviderProfileRegistry profileRegistry) {
        this.registry = registry;
        this.settingsRepository = settingsRepository;
        this.clientFactory = clientFactory;
        this.circuitBreakerManager = circuitBreakerManager;
        this.profileRegistry = profileRegistry;
    }

    public void setSemanticCache(@Nullable SemanticCache semanticCache) {
        this.semanticCache = semanticCache;
    }

    /**
     * 文本生成。
     */
    public LlmResponse call(String scene,
                            String prompt,
                            @Nullable String outputSchema,
                            @Nullable String serviceId,
                            @Nullable String modelName,
                            GenerationCapability requiredCapability,
                            @Nullable Duration timeoutOverride) {
        return call(scene, prompt, outputSchema, serviceId, modelName, requiredCapability, timeoutOverride, false);
    }

    /**
     * 文本生成（支持跳过语义缓存）。
     *
     * @param skipCache true 时跳过语义缓存的读取和写入，适用于每次输入内容不同但模板相似的场景（如实体提取）
     */
    public LlmResponse call(String scene,
                            String prompt,
                            @Nullable String outputSchema,
                            @Nullable String serviceId,
                            @Nullable String modelName,
                            GenerationCapability requiredCapability,
                            @Nullable Duration timeoutOverride,
                            boolean skipCache) {
        String normalizedScene = normalizeScene(scene);
        String responseFormatKey = responseFormatKey(outputSchema);
        boolean cacheable = !skipCache
                && (requiredCapability == GenerationCapability.CHAT
                || requiredCapability == GenerationCapability.STRUCTURED_OUTPUT);
        if (cacheable) {
            LlmResponse cached = lookupCachedResponse(normalizedScene, responseFormatKey, prompt);
            if (cached != null) {
                return cached;
            }
        }

        List<ModelServiceEntity> candidates = selectCandidates(normalizedScene, serviceId, modelName, requiredCapability);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException("无可用生成服务: scene=" + scene, scene, List.of());
        }

        ArrayList<String> attemptedServices = new ArrayList<>();
        Exception lastException = null;
        for (ModelServiceEntity candidate : candidates) {
            attemptedServices.add(candidate.id());
            if (!circuitBreakerManager.isCallPermitted(candidate.id(), requiredCapability.name())) {
                log.debug("生成调用跳过熔断服务: scene={}, serviceId={}, model={}, capability={}",
                        scene, candidate.id(), candidate.modelName(), requiredCapability);
                continue;
            }
            Duration effectiveTimeout = timeoutOverride != null
                    ? timeoutOverride
                    : Duration.ofSeconds(candidate.timeoutSeconds());
            try {
                LlmResponse response = clientFactory.getOrCreate(candidate)
                        .call(prompt, outputSchema, effectiveTimeout);
                circuitBreakerManager.recordSuccess(candidate.id(), requiredCapability.name());
                if (cacheable) {
                    putCachedResponse(normalizedScene, responseFormatKey, prompt, response);
                }
                return response;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(candidate.id(), requiredCapability.name());
                lastException = e;
                Throwable root = rootCause(e);
                log.warn("生成调用失败: scene={}, serviceId={}, model={}, capability={}, timeoutSeconds={}, promptChars={}, errorType={}, rootType={}, error={}",
                        scene,
                        candidate.id(),
                        candidate.modelName(),
                        requiredCapability,
                        effectiveTimeout.toSeconds(),
                        prompt.length(),
                        e.getClass().getSimpleName(),
                        root.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        throw new LlmUnavailableException(
                "所有生成服务调用失败: scene=" + scene, scene, attemptedServices, lastException);
    }

    /**
     * 结构化调用。
     */
    public <T> T callEntity(String scene,
                            String prompt,
                            Class<T> responseType,
                            @Nullable String serviceId,
                            @Nullable String modelName,
                            @Nullable Duration timeoutOverride) {
        List<ModelServiceEntity> candidates = selectCandidates(
                scene, serviceId, modelName, GenerationCapability.STRUCTURED_OUTPUT);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException("无可用生成服务: scene=" + scene, scene, List.of());
        }

        ArrayList<String> attemptedServices = new ArrayList<>();
        Exception lastException = null;
        for (ModelServiceEntity candidate : candidates) {
            attemptedServices.add(candidate.id());
            if (!circuitBreakerManager.isCallPermitted(candidate.id(), GenerationCapability.STRUCTURED_OUTPUT.name())) {
                log.debug("结构化生成跳过熔断服务: scene={}, serviceId={}, model={}",
                        scene, candidate.id(), candidate.modelName());
                continue;
            }
            Duration effectiveTimeout = timeoutOverride != null
                    ? timeoutOverride
                    : Duration.ofSeconds(candidate.timeoutSeconds());
            try {
                T result = clientFactory.getOrCreate(candidate).callEntity(prompt, responseType, effectiveTimeout);
                circuitBreakerManager.recordSuccess(candidate.id(), GenerationCapability.STRUCTURED_OUTPUT.name());
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(candidate.id(), GenerationCapability.STRUCTURED_OUTPUT.name());
                lastException = e;
                Throwable root = rootCause(e);
                log.warn("结构化生成失败: scene={}, serviceId={}, model={}, timeoutSeconds={}, promptChars={}, errorType={}, rootType={}, error={}",
                        scene,
                        candidate.id(),
                        candidate.modelName(),
                        effectiveTimeout.toSeconds(),
                        prompt.length(),
                        e.getClass().getSimpleName(),
                        root.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        throw new LlmUnavailableException(
                "所有生成服务结构化调用失败: scene=" + scene, scene, attemptedServices, lastException);
    }

    /**
     * 获取 ChatClient 与服务信息。
     */
    public ChatClientInfo getChatClientWithInfo(String scene,
                                                @Nullable String serviceId,
                                                @Nullable String modelName) {
        List<ModelServiceEntity> candidates = selectCandidates(scene, serviceId, modelName, GenerationCapability.CHAT);
        for (ModelServiceEntity candidate : candidates) {
            GenerationServiceClient client = clientFactory.getOrCreate(candidate);
            var chatClient = client.chatClient();
            if (chatClient.isPresent()) {
                return new ChatClientInfo(chatClient.get(), candidate.id(), candidate.modelName());
            }
        }
        throw new LlmUnavailableException("无可用 ChatClient: scene=" + scene, scene, List.of());
    }

    /**
     * 获取 ChatModel 与服务信息。
     */
    public ChatModelInfo getChatModelWithInfo(String scene,
                                              @Nullable String serviceId,
                                              @Nullable String modelName) {
        List<ModelServiceEntity> candidates = selectCandidates(scene, serviceId, modelName, GenerationCapability.CHAT);
        for (ModelServiceEntity candidate : candidates) {
            BaseAdapterType baseAdapter = profileRegistry.get(candidate.profileId()).baseAdapter();
            return new ChatModelInfo(
                    clientFactory.getOrCreate(candidate).chatModel(),
                    candidate.id(),
                    candidate.modelName(),
                    baseAdapter,
                    candidate.apiUrl(),
                    candidate.generationCapabilities().contains(GenerationCapability.STREAMING));
        }
        throw new LlmUnavailableException("无可用 ChatModel: scene=" + scene, scene, List.of());
    }

    /**
     * 流式生成（仅产 {@code Flux<String>}，老路径）。
     */
    public StreamingGenerationResponse streamWithInfo(String scene,
                                                      String prompt,
                                                      @Nullable String serviceId,
                                                      @Nullable String modelName) {
        List<ModelServiceEntity> candidates = selectCandidates(scene, serviceId, modelName, GenerationCapability.STREAMING);
        for (ModelServiceEntity candidate : candidates) {
            return new StreamingGenerationResponse(
                    clientFactory.getOrCreate(candidate).stream(prompt),
                    candidate.id(),
                    candidate.modelName());
        }
        throw new LlmUnavailableException("无可用流式生成服务: scene=" + scene, scene, List.of());
    }

    /**
     * 流式生成（产 {@link StreamingLlmResponse}，承载 LlmStreamEvent 多维事件）。
     *
     * <p>用于 StreamingCallback 等需要消费 reasoning / tool_calls / usage 事件的场景。
     * 内部委托适配器 {@code streamEvents(Prompt, List<ToolCallback>)}。
     *
     * @param scene               场景名（用于路由）
     * @param preferredProviderId 优先 Provider ID（可空）
     * @param prompt              已构造好的 Spring AI Prompt
     * @param toolCallbacks       工具回调列表
     * @return 流式响应（含 events / providerId / modelId）
     */
    public StreamingLlmResponse streamWithInfo(String scene,
                                               @Nullable String preferredProviderId,
                                               Prompt prompt,
                                               List<ToolCallback> toolCallbacks) {
        List<ModelServiceEntity> candidates = selectCandidates(
                scene, preferredProviderId, null, GenerationCapability.STREAMING);
        for (ModelServiceEntity candidate : candidates) {
            Flux<LlmStreamEvent> events = clientFactory.getOrCreate(candidate)
                    .streamEvents(prompt, toolCallbacks);
            return new StreamingLlmResponse(events, candidate.id(), candidate.modelName());
        }
        throw new LlmUnavailableException("无可用流式生成服务: scene=" + scene, scene, List.of());
    }

    public int resolveMaxContextWindow(String scene,
                                       @Nullable String serviceId,
                                       @Nullable String modelName) {
        return selectCandidates(scene, serviceId, modelName, GenerationCapability.CHAT).stream()
                .map(service -> service.metadata().get("maxContextWindow"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToInt(Number::intValue)
                .filter(value -> value > 0)
                .findFirst()
                .orElse(0);
    }

    private List<ModelServiceEntity> selectCandidates(String scene,
                                                      @Nullable String serviceId,
                                                      @Nullable String modelName,
                                                      GenerationCapability requiredCapability) {
        LinkedHashSet<ModelServiceEntity> ordered = new LinkedHashSet<>();

        registry.findEnabledById(ModelServiceKind.GENERATION, serviceId)
                .filter(service -> service.generationCapabilities().contains(requiredCapability))
                .ifPresent(ordered::add);

        registry.findEnabledByModelName(ModelServiceKind.GENERATION, modelName).stream()
                .filter(service -> service.generationCapabilities().contains(requiredCapability))
                .forEach(ordered::add);

        GenerationSettingsEntity settings = settingsRepository.findDefault()
                .orElse(new GenerationSettingsEntity(GenerationSettingsRepository.DEFAULT_ID, null, Map.of()));

        String boundServiceId = settings.sceneServiceBindings().get(scene);
        registry.findEnabledById(ModelServiceKind.GENERATION, boundServiceId)
                .filter(service -> service.generationCapabilities().contains(requiredCapability))
                .ifPresent(ordered::add);

        registry.findEnabledById(ModelServiceKind.GENERATION, settings.defaultServiceId())
                .filter(service -> service.generationCapabilities().contains(requiredCapability))
                .ifPresent(ordered::add);

        registry.findGenerationCandidates(scene, requiredCapability).forEach(ordered::add);
        registry.findEnabledByKind(ModelServiceKind.GENERATION).stream()
                .filter(service -> service.generationCapabilities().contains(requiredCapability))
                .forEach(ordered::add);

        return List.copyOf(ordered);
    }

    @Nullable
    private LlmResponse lookupCachedResponse(String scene, String responseFormatKey, String prompt) {
        if (semanticCache == null) {
            return null;
        }
        try {
            return semanticCache.lookup(scene, null, responseFormatKey, prompt)
                    .map(entry -> LlmResponse.cached(entry.responseText(), "cache", entry.modelName()))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("生成缓存查询异常，跳过缓存: scene={}, error={}", scene, e.getMessage());
            return null;
        }
    }

    private void putCachedResponse(String scene, String responseFormatKey, String prompt, LlmResponse response) {
        if (semanticCache == null || response.cached()) {
            return;
        }
        try {
            semanticCache.putAsync(scene, null, responseFormatKey, prompt, response.content(), response.modelName());
        } catch (Exception e) {
            log.warn("生成缓存写入异常，跳过缓存: scene={}, error={}", scene, e.getMessage());
        }
    }

    private static String responseFormatKey(@Nullable String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return "text";
        }
        return "json:" + sha256Hex(outputSchema.trim());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String normalizeScene(String scene) {
        return scene == null ? "" : scene.trim();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * ChatClient 及其元信息。
     */
    public record ChatClientInfo(ChatClient client, String serviceId, String modelName) {
    }

    /**
     * ChatModel 及其元信息。
     */
    public record ChatModelInfo(ChatModel chatModel, String serviceId, String modelName,
                                BaseAdapterType baseAdapter, String apiUrl,
                                boolean supportsStreaming) {
    }

    /**
     * 流式生成响应。
     */
    public record StreamingGenerationResponse(Flux<String> stream, String serviceId, String modelName) {
    }
}
