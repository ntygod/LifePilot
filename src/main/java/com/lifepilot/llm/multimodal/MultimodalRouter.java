package com.lifepilot.llm.multimodal;

import com.lifepilot.llm.ExponentialBackoff;
import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.StreamingLlmResponse;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.multimodal.gemini.GeminiFileApiClient;
import com.lifepilot.llm.multimodal.gemini.GeminiFileUploadResult;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.media.video.VideoProcessResult;
import com.lifepilot.media.video.VideoProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 多模态路由入口。
 *
 * <p>处理包含图片或视频的 LLM 调用请求。当请求不包含图片时，
 * 委托给 {@link LlmRouter} 处理纯文本请求。当请求包含图片时，
 * 自行执行 VISION 能力过滤、熔断器过滤、优先级排序和故障转移。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
public class MultimodalRouter {

    private static final Logger log = LoggerFactory.getLogger(MultimodalRouter.class);

    private final ProviderRegistry providerRegistry;
    private final CircuitBreakerManager circuitBreakerManager;
    private final MediaProcessor mediaProcessor;
    private final MediaValidator mediaValidator;
    private final @Nullable VideoProcessor videoProcessor;
    private final @Nullable GeminiFileApiClient geminiFileApiClient;
    private final MediaProperties mediaProperties;
    private final LlmRouter llmRouter;
    private final ExponentialBackoff backoff;

    /** LRU 缓存容量上限。 */
    private static final int CACHE_MAX_SIZE = 50;
    /** 缓存条目 TTL（秒）。 */
    private static final long CACHE_TTL_SECONDS = 600;

    /**
     * 预处理缓存条目。
     *
     * @param processedImages 已处理的图片列表
     * @param createdAt       缓存创建时间
     */
    record CacheEntry(List<MediaContent> processedImages, Instant createdAt) {
        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }

    /** 基于 MediaContent id 的 LRU 预处理缓存。 */
    private final Map<String, CacheEntry> preprocessCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_SIZE || eldest.getValue().isExpired();
                }
            }
    );

    public MultimodalRouter(ProviderRegistry providerRegistry,
                            CircuitBreakerManager circuitBreakerManager,
                            MediaProcessor mediaProcessor,
                            MediaValidator mediaValidator,
                            @Nullable VideoProcessor videoProcessor,
                            @Nullable GeminiFileApiClient geminiFileApiClient,
                            MediaProperties mediaProperties,
                            LlmRouter llmRouter) {
        this.providerRegistry = providerRegistry;
        this.circuitBreakerManager = circuitBreakerManager;
        this.mediaProcessor = mediaProcessor;
        this.mediaValidator = mediaValidator;
        this.videoProcessor = videoProcessor;
        this.geminiFileApiClient = geminiFileApiClient;
        this.mediaProperties = mediaProperties;
        this.llmRouter = llmRouter;
        this.backoff = ExponentialBackoff.defaults();
        log.info("MultimodalRouter 初始化完成, 视频处理={}, 原生视频={}",
                videoProcessor != null ? "已启用" : "未启用",
                geminiFileApiClient != null ? "已启用" : "未启用");
    }

    public LlmResponse call(MultimodalRequest request) {
        return call(request, null);
    }

    public LlmResponse call(MultimodalRequest request, @Nullable Duration timeoutOverride) {
        var preprocessed = preprocessVideo(request);
        String text = preprocessed.text();
        List<MediaContent> mediaList = preprocessed.mediaList();

        boolean hasImages = mediaList.stream().anyMatch(mc -> mc.mimeType().startsWith("image/"));
        if (!hasImages) {
            log.debug("无图片附件，委托 LlmRouter: scene={}", request.scene());
            var llmRequest = LlmRequest.builder(request.scene(), text)
                    .outputSchema(request.outputSchema())
                    .modelName(request.modelName())
                    .preferredProviderId(request.preferredProviderId())
                    .timeoutOverride(timeoutOverride)
                    .build();
            return llmRouter.call(llmRequest);
        }

        List<MediaContent> processedImages = getProcessedImages(mediaList);

        var candidates = resolveVisionCandidates(request);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 VISION Provider: scene=" + request.scene(),
                    request.scene(),
                    List.of()
            );
        }

        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;
        for (int i = 0; i < candidates.size(); i++) {
            var config = candidates.get(i);
            attemptedProviders.add(config.id());

            try {
                var adapter = providerRegistry.getAdapter(config.id());
                var response = adapter.callWithMedia(
                        text,
                        processedImages,
                        effectiveTimeout(config, timeoutOverride)
                );
                circuitBreakerManager.recordSuccess(config.id(), "VISION");
                log.debug("多模态调用成功: scene={}, provider={}, latency={}ms",
                        request.scene(), config.id(), response.latencyMs());
                return response;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(config.id(), "VISION");
                lastException = e;
                log.warn("多模态调用失败: scene={}, provider={}, error={}",
                        request.scene(), config.id(), e.getMessage());
                if (i < candidates.size() - 1) {
                    sleepBackoff(i);
                }
            }
        }

        throw new LlmUnavailableException(
                "所有 VISION Provider 调用失败: scene=" + request.scene(),
                request.scene(),
                attemptedProviders,
                lastException
        );
    }

    public Flux<String> stream(MultimodalRequest request) {
        return streamWithInfo(request).stream();
    }

    public StreamingLlmResponse streamWithInfo(MultimodalRequest request) {
        var preprocessed = preprocessVideo(request);
        String text = preprocessed.text();
        List<MediaContent> mediaList = preprocessed.mediaList();

        boolean hasImages = mediaList.stream().anyMatch(mc -> mc.mimeType().startsWith("image/"));
        if (!hasImages) {
            log.debug("无图片附件，委托 LlmRouter.stream: scene={}", request.scene());
            if (request.modelName() != null && !request.modelName().isBlank()) {
                return llmRouter.streamWithInfo(request.scene(), text);
            }
            return llmRouter.streamWithInfo(request.scene(), text, request.preferredProviderId());
        }

        // 使用缓存获取预处理后的图片
        List<MediaContent> processedImages = getProcessedImages(mediaList);

        var candidates = resolveVisionCandidates(request);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 VISION Provider: scene=" + request.scene(),
                    request.scene(),
                    List.of()
            );
        }

        var config = candidates.getFirst();
        var adapter = providerRegistry.getAdapter(config.id());
        log.debug("多模态流式调用: scene={}, provider={}", request.scene(), config.id());
        return new StreamingLlmResponse(
                adapter.streamWithMedia(text, processedImages),
                config.id(),
                config.modelName()
        );
    }

    private MultimodalRequest preprocessVideo(MultimodalRequest request) {
        if (!request.hasVideos()) {
            return request;
        }

        MediaContent videoContent = request.mediaList().stream()
                .filter(mc -> mc.mimeType().startsWith("video/"))
                .findFirst()
                .orElse(null);
        if (videoContent == null) {
            return request;
        }

        // 尝试原生视频路由：enabled + GeminiFileApiClient 可用 + 存在 NATIVE_VIDEO Provider
        if (mediaProperties.getNativeVideo().isEnabled() && geminiFileApiClient != null) {
            var nativeProviders = providerRegistry.findByCapability(ProviderCapability.NATIVE_VIDEO);
            if (!nativeProviders.isEmpty()) {
                try {
                    log.info("视频路由策略: 原生视频, fileName={}, size={}B",
                            videoContent.fileName(), videoContent.sizeBytes());
                    GeminiFileUploadResult uploadResult = geminiFileApiClient.upload(
                            videoContent.data(), videoContent.mimeType(), videoContent.fileName());
                    geminiFileApiClient.awaitActive(uploadResult.fileUri());

                    var config = nativeProviders.getFirst();
                    var adapter = providerRegistry.getAdapter(config.id());
                    var timeout = Duration.ofSeconds(config.timeoutSeconds());
                    LlmResponse videoResponse = adapter.callWithVideo(
                            request.text(), uploadResult.fileUri(), timeout);

                    log.info("原生视频调用成功: provider={}, latency={}ms",
                            config.id(), videoResponse.latencyMs());

                    // 返回空媒体列表的请求，call() 方法会检测无图片后委托 LlmRouter，
                    // 但此处已获得结果，需要直接返回。通过移除视频内容使后续流程跳过多模态调用。
                    List<MediaContent> nonVideoItems = request.mediaList().stream()
                            .filter(mc -> !mc.mimeType().startsWith("video/"))
                            .toList();
                    return new MultimodalRequest(
                            request.scene(),
                            request.text(),
                            nonVideoItems,
                            request.outputSchema(),
                            request.preferredProviderId(),
                            request.modelName()
                    );
                } catch (Exception e) {
                    log.warn("原生视频路由失败，回退到关键帧分治: error={}", e.getMessage());
                }
            }
        }

        // 回退到关键帧分治策略
        VideoProcessor processor = this.videoProcessor;
        if (processor == null) {
            return request;
        }

        log.info("视频路由策略: 关键帧分治, fileName={}, size={}B",
                videoContent.fileName(), videoContent.sizeBytes());
        VideoProcessResult result = processor.process(videoContent.data(), videoContent.mimeType());

        List<MediaContent> nonVideoItems = request.mediaList().stream()
                .filter(mc -> !mc.mimeType().startsWith("video/"))
                .toList();
        List<MediaContent> newMediaList = Stream.concat(
                nonVideoItems.stream(),
                result.keyFrames().stream()
        ).toList();

        String newText = request.text();
        String transcript = result.transcript();
        if (transcript != null && !transcript.isBlank()) {
            newText = newText + "\n\n[视频音轨转录]\n" + transcript;
        }

        return new MultimodalRequest(
                request.scene(),
                newText,
                newMediaList,
                request.outputSchema(),
                request.preferredProviderId(),
                request.modelName()
        );
    }

    private List<ProviderConfig> resolveVisionCandidates(MultimodalRequest request) {
        if (request.modelName() != null && !request.modelName().isBlank()) {
            return providerRegistry.findByModelName(request.modelName()).stream()
                    .filter(config -> config.hasCapability(ProviderCapability.VISION))
                    .filter(config -> circuitBreakerManager.isCallPermitted(config.id(), "VISION"))
                    .toList();
        }
        return llmRouter.getAvailableCandidates(
                request.scene(),
                ProviderCapability.VISION,
                request.preferredProviderId()
        );
    }

    private Duration effectiveTimeout(ProviderConfig config, @Nullable Duration timeoutOverride) {
        return timeoutOverride != null ? timeoutOverride : Duration.ofSeconds(config.timeoutSeconds());
    }

    private void sleepBackoff(int attempt) {
        long delay = backoff.delayForAttempt(attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 基于媒体内容 id 列表构建缓存键。
     *
     * @param mediaList 媒体内容列表
     * @return 缓存键字符串
     */
    private String buildCacheKey(List<MediaContent> mediaList) {
        return mediaList.stream()
                .map(MediaContent::id)
                .collect(Collectors.joining("|"));
    }

    /**
     * 获取预处理后的图片列表（优先从缓存读取）。
     * <p>
     * 缓存命中且未过期时跳过校验和压缩，直接返回缓存结果。
     * 缓存未命中时执行校验 + 压缩，并将结果存入缓存。
     *
     * @param mediaList 原始媒体内容列表
     * @return 预处理后的图片列表
     */
    private List<MediaContent> getProcessedImages(List<MediaContent> mediaList) {
        String cacheKey = buildCacheKey(mediaList);
        CacheEntry cached = preprocessCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("命中预处理缓存: cacheKey={}", cacheKey);
            return cached.processedImages();
        }

        // 缓存未命中，执行校验 + 压缩
        mediaValidator.validateAll(mediaList);
        List<MediaContent> images = mediaList.stream()
                .filter(mc -> mc.mimeType().startsWith("image/"))
                .toList();
        List<MediaContent> processedImages = mediaProcessor.processAll(images);
        preprocessCache.put(cacheKey, new CacheEntry(processedImages, Instant.now()));
        return processedImages;
    }
}
