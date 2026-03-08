package com.lifepilot.meta.infra.interaction;

import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.meta.config.MetaProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 交互桥接器 — 管理 Agent 与用户之间的交互请求/响应生命周期。
 *
 * <p>核心机制：</p>
 * <ol>
 *   <li>工具 Executor 调用 {@link #request(InteractionRequest)} 发起交互请求</li>
 *   <li>根据可用 Channel 推送交互请求（SSE / CLI）</li>
 *   <li>通过 {@link CompletableFuture#get(long, TimeUnit)} 阻塞等待用户响应</li>
 *   <li>外部调用 {@link #resolve(String, InteractionResponse)} 完成 Future</li>
 *   <li>超时未响应时返回超时响应并清理</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class InteractionBridge {

    private static final Logger log = LoggerFactory.getLogger(InteractionBridge.class);

    private final ConcurrentHashMap<String, CompletableFuture<InteractionResponse>> pendingRequests =
            new ConcurrentHashMap<>();

    private final MetaProperties properties;
    @Nullable
    private final SseSessionManager sseSessionManager;
    @Nullable
    private final CliInteractionHandler cliInteractionHandler;

    public InteractionBridge(MetaProperties properties,
                             @Nullable SseSessionManager sseSessionManager,
                             @Nullable CliInteractionHandler cliInteractionHandler) {
        this.properties = properties;
        this.sseSessionManager = sseSessionManager;
        this.cliInteractionHandler = cliInteractionHandler;
    }

    /**
     * 发起交互请求并阻塞等待用户响应。
     *
     * <p>生成 interactionId，创建 CompletableFuture，通过可用 Channel 推送请求，
     * 然后阻塞等待用户响应或超时。</p>
     *
     * @param request 交互请求（interactionId 字段将被忽略，由本方法生成）
     * @return 用户响应或超时响应
     */
    public InteractionResponse request(InteractionRequest request) {
        if (sseSessionManager == null && cliInteractionHandler == null) {
            log.error("无可用交互通道: sseSessionManager 和 cliInteractionHandler 均为 null");
            return new InteractionResponse(
                    request.interactionId() != null ? request.interactionId() : UUID.randomUUID().toString(),
                    null, false, true
            );
        }

        String interactionId = UUID.randomUUID().toString();
        var enrichedRequest = new InteractionRequest(
                interactionId,
                request.type(),
                request.sessionId(),
                request.message(),
                request.options()
        );

        var future = new CompletableFuture<InteractionResponse>();
        pendingRequests.put(interactionId, future);

        try {
            // 通过可用 Channel 推送交互请求
            pushToChannel(enrichedRequest);

            // 阻塞等待用户响应
            int timeoutSeconds = properties.getInfra().getInteraction().getResponseTimeoutSeconds();
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("交互请求超时: interactionId={}, type={}", interactionId, request.type());
            return InteractionResponse.timeout(interactionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("交互请求被中断: interactionId={}", interactionId);
            return InteractionResponse.timeout(interactionId);
        } catch (ExecutionException e) {
            log.error("交互请求执行异常: interactionId={}", interactionId, e.getCause());
            return InteractionResponse.timeout(interactionId);
        } finally {
            pendingRequests.remove(interactionId);
        }
    }

    /**
     * 完成待处理的交互请求。
     *
     * <p>由外部调用（如 REST 信号回传端点）来完成 CompletableFuture。</p>
     *
     * @param interactionId 交互唯一标识
     * @param response      用户响应
     */
    public void resolve(String interactionId, InteractionResponse response) {
        var future = pendingRequests.remove(interactionId);
        if (future != null) {
            future.complete(response);
            log.debug("交互请求已完成: interactionId={}", interactionId);
        } else {
            log.warn("交互请求不存在或已超时: interactionId={}", interactionId);
        }
    }

    /**
     * 推送通知（非阻塞）— 仅推送消息，不等待响应。
     *
     * @param request 通知请求（type 必须为 NOTIFY）
     */
    public void notify(InteractionRequest request) {
        if (sseSessionManager == null && cliInteractionHandler == null) {
            log.warn("无可用交互通道，通知推送失败: message={}", request.message());
            return;
        }

        String interactionId = UUID.randomUUID().toString();
        var enrichedRequest = new InteractionRequest(
                interactionId,
                InteractionType.NOTIFY,
                request.sessionId(),
                request.message(),
                null
        );

        pushToChannel(enrichedRequest);
        log.debug("通知已推送: interactionId={}, message={}", interactionId, request.message());
    }

    /**
     * 获取当前待处理请求数量（用于监控和测试）。
     *
     * @return 待处理请求数
     */
    public int pendingCount() {
        return pendingRequests.size();
    }

    /** 通过可用 Channel 推送交互请求。 */
    private void pushToChannel(InteractionRequest request) {
        // 优先使用 SSE Channel（Web）
        if (sseSessionManager != null) {
            sseSessionManager.sendEvent(request.sessionId(), "interaction", request);
            log.debug("交互请求已通过 SSE 推送: interactionId={}, sessionId={}",
                    request.interactionId(), request.sessionId());
            return;
        }

        // 降级到 CLI Channel
        if (cliInteractionHandler != null) {
            cliInteractionHandler.pushInteraction(request);
            log.debug("交互请求已通过 CLI 推送: interactionId={}", request.interactionId());
        }
    }
}
