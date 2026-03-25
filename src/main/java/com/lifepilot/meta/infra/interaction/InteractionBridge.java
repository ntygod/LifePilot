package com.lifepilot.meta.infra.interaction;

import com.lifepilot.interaction.web.sse.SseEventType;
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
 * 交互桥接器，管理 Agent 与用户之间的交互请求和响应。
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
                request.streamId(),
                request.message(),
                request.options()
        );

        var future = new CompletableFuture<InteractionResponse>();
        pendingRequests.put(interactionId, future);

        try {
            boolean pushed = pushToChannel(enrichedRequest);
            if (!pushed) {
                return InteractionResponse.timeout(interactionId);
            }

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

    public void resolve(String interactionId, InteractionResponse response) {
        var future = pendingRequests.remove(interactionId);
        if (future != null) {
            future.complete(response);
            log.debug("交互请求已完成: interactionId={}", interactionId);
        } else {
            log.warn("交互请求不存在或已超时: interactionId={}", interactionId);
        }
    }

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
                request.streamId(),
                request.message(),
                null
        );

        pushToChannel(enrichedRequest);
        log.debug("通知已推送: interactionId={}, sessionId={}, streamId={}",
                interactionId, request.sessionId(), request.streamId());
    }

    public int pendingCount() {
        return pendingRequests.size();
    }

    private boolean pushToChannel(InteractionRequest request) {
        if (sseSessionManager != null) {
            if (request.streamId() != null && !request.streamId().isBlank()) {
                if (sseSessionManager.getEmitter(request.streamId()) == null) {
                    log.warn("SSE 流不存在，尝试按会话兜底路由交互请求: interactionId={}, streamId={}, sessionId={}",
                            request.interactionId(), request.streamId(), request.sessionId());
                } else {
                    sseSessionManager.sendEvent(request.streamId(), SseEventType.INTERACTION, request);
                    log.debug("交互请求已通过 SSE 精确推送: interactionId={}, streamId={}, sessionId={}",
                            request.interactionId(), request.streamId(), request.sessionId());
                    return true;
                }
            }

            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                String mappedStreamId = sseSessionManager.findChatStreamId(request.sessionId());
                if (mappedStreamId != null) {
                    var routedRequest = new InteractionRequest(
                            request.interactionId(),
                            request.type(),
                            request.sessionId(),
                            mappedStreamId,
                            request.message(),
                            request.options()
                    );
                    sseSessionManager.sendEvent(mappedStreamId, SseEventType.INTERACTION, routedRequest);
                    log.debug("交互请求已按会话兜底推送到活动流: interactionId={}, sessionId={}, streamId={}",
                            request.interactionId(), request.sessionId(), mappedStreamId);
                    return true;
                }

                if (sseSessionManager.getEmitter(request.sessionId()) != null) {
                    sseSessionManager.sendEvent(request.sessionId(), SseEventType.INTERACTION, request);
                    log.debug("交互请求已通过 SSE 会话推送: interactionId={}, sessionId={}",
                            request.interactionId(), request.sessionId());
                    return true;
                }
            }

            log.warn("SSE 连接不存在，交互请求无法送达: interactionId={}, streamId={}, sessionId={}",
                    request.interactionId(), request.streamId(), request.sessionId());
            return false;
        }

        if (cliInteractionHandler != null) {
            cliInteractionHandler.pushInteraction(request);
            log.debug("交互请求已通过 CLI 推送: interactionId={}", request.interactionId());
            return true;
        }

        return false;
    }
}
