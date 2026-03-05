package com.lifepilot.llm.multimodal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.llm.ExponentialBackoff;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.StreamingLlmResponse;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.video.VideoProcessResult;
import com.lifepilot.media.video.VideoProcessor;

import reactor.core.publisher.Flux;

/**
 * 多模态路由入口。
 *
 * <p>处理包含图片或视频的 LLM 调用请求。当请求不包含图片时，
 * 委托给 {@link LlmRouter} 处理纯文本请求。当请求包含图片时，
 * 自行执行 VISION 能力过滤、熔断器过滤、优先级排序和故障转移。</p>
 *
 * <p>视频请求先通过 {@link VideoProcessor} 预处理，将视频拆分为关键帧和音轨转录文本，
 * 再按图片理解流程处理。</p>
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
    private final LlmRouter llmRouter;
    private final ExponentialBackoff backoff;

    /**
     * 构造多模态路由器。
     *
     * @param providerRegistry      Provider 注册表
     * @param circuitBreakerManager 熔断器管理器
     * @param mediaProcessor        图片预处理器
     * @param mediaValidator        媒体校验器
     * @param videoProcessor        视频预处理器（可选，未配置时不支持视频）
     * @param llmRouter             LLM 路由器（纯文本回退）
     */
    public MultimodalRouter(
            ProviderRegistry providerRegistry,
            CircuitBreakerManager circuitBreakerManager,
            MediaProcessor mediaProcessor,
            MediaValidator mediaValidator,
            @Nullable VideoProcessor videoProcessor,
            LlmRouter llmRouter) {
        this.providerRegistry = providerRegistry;
        this.circuitBreakerManager = circuitBreakerManager;
        this.mediaProcessor = mediaProcessor;
        this.mediaValidator = mediaValidator;
        this.videoProcessor = videoProcessor;
        this.llmRouter = llmRouter;
        this.backoff = ExponentialBackoff.defaults();
        log.info("MultimodalRouter 初始化完成, 视频处理={}", videoProcessor != null ? "已启用" : "未启用");
    }

    /**
     * 执行多模态同步调用。
     *
     * <p>处理流程：
     * <ol>
     *   <li>视频预处理（如有视频且 VideoProcessor 可用）</li>
     *   <li>无图片 → 委托 {@link LlmRouter#call(String, String, String)}</li>
     *   <li>有图片 → 校验 → 预处理 → VISION 过滤 → 故障转移循环</li>
     * </ol>
     *
     * @param request 多模态请求
     * @return LLM 响应
     * @throws LlmUnavailableException 所有 VISION Provider 均不可用
     */
    public LlmResponse call(MultimodalRequest request) {
        // 1. 视频预处理
        var preprocessed = preprocessVideo(request);
        String text = preprocessed.text();
        List<MediaContent> mediaList = preprocessed.mediaList();

        // 2. 无图片 → 委托纯文本路由
        boolean hasImages = mediaList.stream()
                .anyMatch(mc -> mc.mimeType().startsWith("image/"));
        if (!hasImages) {
            log.debug("无图片附件，委托 LlmRouter: scene={}", request.scene());
            return llmRouter.call(request.scene(), text, request.outputSchema());
        }

        // 3. 有图片 → 校验 + 预处理
        mediaValidator.validateAll(mediaList);

        List<MediaContent> images = mediaList.stream()
                .filter(mc -> mc.mimeType().startsWith("image/"))
                .toList();
        List<MediaContent> processedImages = mediaProcessor.processAll(images);

        // 4. VISION 能力过滤 + 熔断器过滤
        var candidates = providerRegistry.findByScene(request.scene()).stream()
                .filter(c -> c.hasCapability(ProviderCapability.VISION))
                .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), "VISION"))
                .toList();

        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 VISION Provider: scene=" + request.scene(),
                    request.scene(), List.of());
        }

        // 5. 故障转移循环
        var attemptedProviders = new ArrayList<String>();
        Exception lastException = null;

        for (int i = 0; i < candidates.size(); i++) {
            var config = candidates.get(i);
            attemptedProviders.add(config.id());

            try {
                var adapter = providerRegistry.getAdapter(config.id());
                var timeout = Duration.ofSeconds(config.timeoutSeconds());
                var response = adapter.callWithMedia(text, processedImages, timeout);
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
                request.scene(), attemptedProviders, lastException);
    }

    /**
     * 执行多模态流式调用。
     *
     * <p>路由策略与同步调用一致，但不执行中途故障转移（与 {@link LlmRouter#stream} 行为一致）。</p>
     *
     * @param request 多模态请求
     * @return 流式文本响应
     * @throws LlmUnavailableException 无可用 VISION Provider
     */
    public Flux<String> stream(MultimodalRequest request) {
        return streamWithInfo(request).stream();
    }

    /**
     * 执行多模态流式调用（返回附带 Provider/模型信息的响应包装）。
     *
     * <p>用于 SSE/流式通道补齐可观测性数据（例如 modelId）。</p>
     */
    public StreamingLlmResponse streamWithInfo(MultimodalRequest request) {
        // 1. 视频预处理
        var preprocessed = preprocessVideo(request);
        String text = preprocessed.text();
        List<MediaContent> mediaList = preprocessed.mediaList();

        // 2. 无图片 → 委托纯文本流式路由
        boolean hasImages = mediaList.stream()
                .anyMatch(mc -> mc.mimeType().startsWith("image/"));
        if (!hasImages) {
            log.debug("无图片附件，委托 LlmRouter.stream: scene={}", request.scene());
            return llmRouter.streamWithInfo(request.scene(), text);
        }

        // 3. 有图片 → 校验 + 预处理
        mediaValidator.validateAll(mediaList);

        List<MediaContent> images = mediaList.stream()
                .filter(mc -> mc.mimeType().startsWith("image/"))
                .toList();
        List<MediaContent> processedImages = mediaProcessor.processAll(images);

        // 4. VISION 能力过滤 + 熔断器过滤
        var candidates = providerRegistry.findByScene(request.scene()).stream()
                .filter(c -> c.hasCapability(ProviderCapability.VISION))
                .filter(c -> circuitBreakerManager.isCallPermitted(c.id(), "VISION"))
                .toList();

        if (candidates.isEmpty()) {
            throw new LlmUnavailableException(
                    "无可用 VISION Provider: scene=" + request.scene(),
                    request.scene(), List.of());
        }

        // 5. 选择第一个可用 Provider（不执行中途故障转移）
        var config = candidates.getFirst();
        var adapter = providerRegistry.getAdapter(config.id());
        log.debug("多模态流式调用: scene={}, provider={}", request.scene(), config.id());
        return new StreamingLlmResponse(adapter.streamWithMedia(text, processedImages), config.id(), config.modelName());
    }

    // --- 内部方法 ---

    /**
     * 视频预处理：若请求包含视频且 VideoProcessor 可用，
     * 将视频拆分为关键帧和音轨转录文本。
     *
     * @param request 原始请求
     * @return 预处理后的请求（text 可能追加转录文本，mediaList 可能替换视频为关键帧）
     */
    private MultimodalRequest preprocessVideo(MultimodalRequest request) {
        VideoProcessor vp = this.videoProcessor;
        if (!request.hasVideos() || vp == null) {
            return request;
        }

        // 找到第一个视频附件
        MediaContent videoContent = request.mediaList().stream()
                .filter(mc -> mc.mimeType().startsWith("video/"))
                .findFirst()
                .orElse(null);

        if (videoContent == null) {
            return request;
        }

        log.debug("开始视频预处理: fileName={}, 大小={}B", videoContent.fileName(), videoContent.sizeBytes());
        VideoProcessResult result = vp.process(videoContent.data(), videoContent.mimeType());

        // 构建新的 mediaList：原始非视频项 + 关键帧
        List<MediaContent> nonVideoItems = request.mediaList().stream()
                .filter(mc -> !mc.mimeType().startsWith("video/"))
                .toList();
        List<MediaContent> newMediaList = Stream.concat(
                nonVideoItems.stream(),
                result.keyFrames().stream()
        ).toList();

        // 追加转录文本到 text
        String newText = request.text();
        String transcript = result.transcript();
        if (transcript != null && !transcript.isBlank()) {
            newText = newText + "\n\n[视频音轨转录]\n" + transcript;
        }

        return new MultimodalRequest(request.scene(), newText, newMediaList, request.outputSchema());
    }

    /**
     * 指数退避等待。
     *
     * @param attempt 重试次数（从 0 开始）
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
