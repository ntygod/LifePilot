package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.execution.ExecutionRetrySupport;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.*;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行中间件。
 *
 * @author zsg
 * @since 2026-03-25
 */
public class ExecutionMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMiddleware.class);
    private static final String DEFAULT_MODEL_ID = "agent";

    private final AgentOrchestrator agentOrchestrator;
    private final AgentConfigProperties agentConfigProperties;
    private final GatewayProperties properties;
    private final ExecutionRequestFactory requestFactory;
    /** 使用 virtual thread 执行 Agent 任务，避免阻塞 ForkJoinPool.commonPool() */
    private final ExecutorService agentExecutor;
    @Nullable
    private final ChatTurnService chatTurnService;
    @Nullable
    private final SseSessionManager sseSessionManager;

    public ExecutionMiddleware(AgentOrchestrator agentOrchestrator,
                               AgentConfigProperties agentConfigProperties,
                               GatewayProperties properties,
                               ChatSessionRepository chatSessionRepository,
                               @Nullable SseSessionManager sseSessionManager) {
        this(agentOrchestrator, agentConfigProperties, properties, chatSessionRepository, null, sseSessionManager, null);
    }

    public ExecutionMiddleware(AgentOrchestrator agentOrchestrator,
                               AgentConfigProperties agentConfigProperties,
                               GatewayProperties properties,
                               ChatSessionRepository chatSessionRepository,
                               ChatTurnService chatTurnService,
                               @Nullable SseSessionManager sseSessionManager) {
        this(agentOrchestrator, agentConfigProperties, properties, chatSessionRepository,
                chatTurnService, sseSessionManager, null);
    }

    public ExecutionMiddleware(AgentOrchestrator agentOrchestrator,
                               AgentConfigProperties agentConfigProperties,
                               GatewayProperties properties,
                               ChatSessionRepository chatSessionRepository,
                               @Nullable ChatTurnService chatTurnService,
                               @Nullable SseSessionManager sseSessionManager,
                               @Nullable ExecutorService agentExecutor) {
        this.agentOrchestrator = agentOrchestrator;
        this.agentConfigProperties = agentConfigProperties;
        this.properties = properties;
        this.requestFactory = new ExecutionRequestFactory(agentConfigProperties, chatSessionRepository);
        this.agentExecutor = agentExecutor != null
                ? agentExecutor
                : java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        this.chatTurnService = chatTurnService;
        this.sseSessionManager = sseSessionManager;
    }

    /**
     * 执行中间件主入口。
     *
     * <p>这里只做同步/流式分流，具体请求装配、编排、重试与 turn 回写分别委托给下游组件。
     */
    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        if (isStreamingRequest(message)) {
            return processStreaming(message);
        }
        return processSync(message, chain);
    }

    /** 判断当前请求是否应走 Web SSE 流式返回。 */
    private boolean isStreamingRequest(GatewayMessage message) {
        DeliveryMode deliveryMode = resolveDeliveryMode(message);
        if (deliveryMode == DeliveryMode.SSE_STREAM) {
            return true;
        }
        return message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata
                && webMetadata.acceptsSse();
    }

    private DeliveryMode resolveDeliveryMode(GatewayMessage message) {
        String rawMode = message.traceHeaders().get(InteractionTraceHeaders.DELIVERY_MODE);
        if (rawMode == null || rawMode.isBlank()) {
            return DeliveryMode.SYNC;
        }
        try {
            return DeliveryMode.valueOf(rawMode);
        } catch (IllegalArgumentException ignored) {
            return DeliveryMode.SYNC;
        }
    }

    /**
     * 处理同步请求。
     *
     * <p>同步路径会在拿到完整 AgentResponse 后一次性返回；对于首轮即失败且符合瞬时故障特征的情况，允许在中间件层做有限自动重试。
     */
    private GatewayResponse processSync(GatewayMessage message, MiddlewareChain chain) {
        AgentRequest agentRequest = requestFactory.build(message);
        var retryConfig = agentConfigProperties.getExecutionRetry();
        int maxAttempts = retryConfig.isEnabled() ? Math.max(1, retryConfig.getMaxAttempts()) : 1;
        long retryDelayMs = Math.max(0, retryConfig.getInitialDelayMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            CompletableFuture<AgentResponse> future = null;
            try {
                future = CompletableFuture.supplyAsync(() -> agentOrchestrator.run(agentRequest), agentExecutor);
                AgentResponse agentResponse = future.get(properties.execution().timeoutSeconds(), TimeUnit.SECONDS);

                if (shouldRetrySyncResponse(agentResponse) && attempt < maxAttempts) {
                    log.warn("主执行链路触发同步自动重试: sessionId={}, turnId={}, attempt={}/{}, reason={}",
                            agentRequest.sessionId(), agentRequest.turnId(), attempt, maxAttempts,
                            agentResponse.terminationReason());
                    if (!sleepBeforeRetry(retryDelayMs)) {
                        markTurnFailed(message.sessionId(), requestFactory.resolveTurnId(message), 500, "主执行链路自动重试被中断");
                        return GatewayResponse.error(message.channelType(), "主执行链路自动重试被中断", 500);
                    }
                    retryDelayMs = nextRetryDelay(retryDelayMs);
                    continue;
                }

                TokenUsage tokenUsage = agentResponse.tokenUsage() != null
                        ? agentResponse.tokenUsage()
                        : new TokenUsage(0, agentResponse.tokensUsed(), agentResponse.tokensUsed(), DEFAULT_MODEL_ID);
                chain.context().set(MiddlewareContext.KEY_AGENT_RESPONSE, agentResponse);
                chain.context().set(MiddlewareContext.KEY_TOKEN_USAGE, tokenUsage);
                return buildGatewayResponse(message, agentResponse, tokenUsage);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("Agent 执行超时: messageId={}, timeout={}s",
                        message.messageId(), properties.execution().timeoutSeconds());
                markTurnFailed(message.sessionId(), requestFactory.resolveTurnId(message), 504, "请求处理超时");
                return GatewayResponse.error(message.channelType(), "请求处理超时", 504);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (shouldRetrySyncThrowable(cause) && attempt < maxAttempts) {
                    log.warn("主执行链路同步执行异常，准备重试: sessionId={}, turnId={}, attempt={}/{}, error={}",
                            agentRequest.sessionId(), agentRequest.turnId(), attempt, maxAttempts, cause.getMessage());
                    if (!sleepBeforeRetry(retryDelayMs)) {
                        markTurnFailed(message.sessionId(), requestFactory.resolveTurnId(message), 500, "主执行链路自动重试被中断");
                        return GatewayResponse.error(message.channelType(), "主执行链路自动重试被中断", 500);
                    }
                    retryDelayMs = nextRetryDelay(retryDelayMs);
                    continue;
                }
                log.error("Agent 执行异常: messageId={}", message.messageId(), cause);
                markTurnFailed(message.sessionId(), requestFactory.resolveTurnId(message), 500, "处理请求时发生内部错误");
                return GatewayResponse.error(message.channelType(), "处理请求时发生内部错误", 500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Agent 执行被中断: messageId={}", message.messageId());
                markTurnFailed(message.sessionId(), requestFactory.resolveTurnId(message), 500, "请求被中断");
                return GatewayResponse.error(message.channelType(), "请求被中断", 500);
            }
        }

        markTurnFailed(message.sessionId(), requestFactory.resolveTurnId(message), 500, "主执行链路自动重试耗尽");
        return GatewayResponse.error(message.channelType(), "主执行链路自动重试耗尽", 500);
    }

    /**
     * 处理流式请求。
     *
     * <p>这里负责创建 SSE emitter、绑定取消信号和超时控制，然后把真正执行异步交给 orchestrator。
     */
    private GatewayResponse processStreaming(GatewayMessage message) {
        SseSessionManager manager = sseSessionManager;
        if (manager == null) {
            log.warn("流式请求被拒绝，SseSessionManager 不可用: messageId={}", message.messageId());
            return GatewayResponse.error(message.channelType(), "流式响应暂不可用", 503);
        }

        String streamId = UUID.randomUUID().toString();
        manager.createEmitter(streamId);
        if (message.sessionId() != null && !message.sessionId().isBlank()) {
            manager.bindChatSession(message.sessionId(), streamId);
        }

        GatewayResponse response = GatewayResponse.success(
                message.channelType(),
                new ResponseContent.StreamingContent(streamId)
        );

        CancellationToken cancellationToken = new CancellationToken();
        manager.registerCancellationToken(streamId, cancellationToken);

        int timeoutSeconds = properties.execution().timeoutSeconds();
        AgentRequest agentRequest = requestFactory.build(message);

        CompletableFuture.runAsync(() -> runStreaming(agentRequest, message, streamId, manager, cancellationToken), agentExecutor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                        log.error("流式处理超时: messageId={}, streamId={}, timeout={}s",
                                message.messageId(), streamId, timeoutSeconds);
                        cancellationToken.cancel();
                        markTurnFailed(agentRequest.sessionId(), agentRequest.turnId(), 504, "处理超时，请稍后重试");
                        if (manager.getEmitter(streamId) != null) {
                            Map<String, Object> errorData = buildStreamingErrorData(
                                    504,
                                    "处理超时，请稍后重试",
                                    agentRequest.turnId(),
                                    ChatTurnStatus.FAILED
                            );
                            manager.sendEvent(streamId, SseEventType.ERROR, errorData);
                            manager.closeEmitter(streamId);
                        }
                    }
                    return null;
                });

        return response;
    }

    /**
     * 在后台线程里执行流式主链路，并处理 RetryableStreamingException。
     *
     * <p>只有在还未彻底结束且未被用户取消时才会继续重试；一旦重试耗尽，就走统一失败收尾。
     */
    private void runStreaming(AgentRequest agentRequest,
                              GatewayMessage message,
                              String streamId,
                              SseSessionManager manager,
                              CancellationToken cancellationToken) {
        var retryConfig = agentConfigProperties.getExecutionRetry();
        int maxAttempts = retryConfig.isEnabled() ? Math.max(1, retryConfig.getMaxAttempts()) : 1;
        long retryDelayMs = Math.max(0, retryConfig.getInitialDelayMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                agentOrchestrator.runStreaming(agentRequest, streamId, manager, cancellationToken);
                return;
            } catch (AgentOrchestrator.RetryableStreamingException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (attempt < maxAttempts && !cancellationToken.isCancelled()) {
                    log.warn("主执行链路触发流式自动重试: sessionId={}, turnId={}, attempt={}/{}, error={}",
                            agentRequest.sessionId(), agentRequest.turnId(), attempt, maxAttempts, cause.getMessage());
                    if (!sleepBeforeRetry(retryDelayMs)) {
                        handleStreamingFailure(agentRequest, message, streamId, manager, "流式执行被中断",
                                new InterruptedException("流式执行被中断"));
                        return;
                    }
                    retryDelayMs = nextRetryDelay(retryDelayMs);
                    continue;
                }
                handleStreamingFailure(agentRequest, message, streamId, manager,
                        "流式处理失败: " + cause.getMessage(), cause);
                return;
            } catch (Exception e) {
                handleStreamingFailure(agentRequest, message, streamId, manager,
                        "流式处理失败: " + e.getMessage(), e);
                return;
            }
        }
    }

    /** 统一处理流式执行失败：回写 turn、发送 ERROR 事件并关闭 SSE。 */
    private void handleStreamingFailure(AgentRequest agentRequest,
                                        GatewayMessage message,
                                        String streamId,
                                        SseSessionManager manager,
                                        String errorMessage,
                                        Throwable error) {
        log.error("流式处理异常: messageId={}, streamId={}", message.messageId(), streamId, error);
        markTurnFailed(agentRequest.sessionId(), agentRequest.turnId(), 500, errorMessage);
        if (manager.getEmitter(streamId) == null) {
            return;
        }
        Map<String, Object> errorData = buildStreamingErrorData(
                500,
                errorMessage,
                agentRequest.turnId(),
                ChatTurnStatus.FAILED
        );
        manager.sendEvent(streamId, SseEventType.ERROR, errorData);
        manager.closeEmitter(streamId);
    }

    /** 构造流式 ERROR 事件负载，前端据此恢复 turn 状态与错误展示。 */
    private Map<String, Object> buildStreamingErrorData(int code,
                                                        String message,
                                                        @Nullable String turnId,
                                                        ChatTurnStatus turnStatus) {
        Map<String, Object> errorData = new LinkedHashMap<>();
        errorData.put("code", code);
        errorData.put("message", message);
        if (turnId != null && !turnId.isBlank()) {
            errorData.put("turnId", turnId);
        }
        errorData.put("turnStatus", turnStatus.name());
        errorData.put("timestamp", Instant.now().toEpochMilli());
        return errorData;
    }

    /** 将 AgentResponse 转换为网关层统一响应，并补齐前端关心的 turn 元数据。 */
    private GatewayResponse buildGatewayResponse(GatewayMessage message,
                                                 AgentResponse agentResponse,
                                                 TokenUsage tokenUsage) {
        Map<String, Object> metadata = buildResponseMetadata(agentResponse);
        if (agentResponse.completionMode() == CompletionMode.NORMAL
                && agentResponse.terminationReason() != null
                && !agentResponse.terminationReason().isBlank()
                && agentResponse.completionReason() != CompletionReason.EXPLICIT_BLOCKED) {
            return GatewayResponse.error(message.channelType(), agentResponse.content(), 500)
                    .toBuilder()
                    .responseId(agentResponse.assistantEntryId())
                    .metadata(metadata)
                    .build();
        }
        return GatewayResponse.success(message.channelType(), new ResponseContent.TextContent(agentResponse.content()))
                .toBuilder()
                .responseId(agentResponse.assistantEntryId())
                .tokenUsage(tokenUsage)
                .metadata(metadata)
                .build();
    }

    /** 仅当同步响应属于“首轮失败且无有效进展”时，才允许自动重试。 */
    private boolean shouldRetrySyncResponse(AgentResponse agentResponse) {
        if (!agentConfigProperties.getExecutionRetry().isEnabled()) {
            return false;
        }
        if (agentResponse.turnStatus() != ChatTurnStatus.FAILED) {
            return false;
        }
        if (agentResponse.stepCount() > 0) {
            return false;
        }
        return ExecutionRetrySupport.isTransientFailure(
                null,
                agentResponse.content(),
                agentResponse.terminationReason()
        );
    }

    /** 基于异常类型和消息判断是否属于可瞬时恢复的故障。 */
    private boolean shouldRetrySyncThrowable(Throwable error) {
        if (!agentConfigProperties.getExecutionRetry().isEnabled()) {
            return false;
        }
        return ExecutionRetrySupport.isTransientFailure(error, error.getMessage());
    }

    /** 计算下一轮退避等待时间，并限制在配置的最大值内。 */
    private long nextRetryDelay(long currentDelayMs) {
        var retryConfig = agentConfigProperties.getExecutionRetry();
        long next = (long) (currentDelayMs * retryConfig.getMultiplier());
        return Math.min(Math.max(next, currentDelayMs), retryConfig.getMaxDelayMs());
    }

    /** 在两次自动重试之间等待；若线程被中断则立即放弃重试。 */
    private boolean sleepBeforeRetry(long delayMs) {
        if (delayMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 收口前端需要的 trace/turn/completion 元数据，避免控制器重复拼装。 */
    private Map<String, Object> buildResponseMetadata(AgentResponse agentResponse) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (agentResponse.turnId() != null && !agentResponse.turnId().isBlank()) {
            metadata.put("turnId", agentResponse.turnId());
        }
        if (agentResponse.traceId() != null && !agentResponse.traceId().isBlank()) {
            metadata.put("traceId", agentResponse.traceId());
        }
        metadata.put("taskMode", agentResponse.taskMode().name());
        metadata.put("completionMode", agentResponse.completionMode().name());
        if (agentResponse.completionReason() != null) {
            metadata.put("completionReason", agentResponse.completionReason().name());
        }
        metadata.put("turnStatus", agentResponse.turnStatus().name());
        if (agentResponse.resumedFromTraceId() != null && !agentResponse.resumedFromTraceId().isBlank()) {
            metadata.put("resumedFromTraceId", agentResponse.resumedFromTraceId());
        }
        if (agentResponse.a2uiComponents() != null && !agentResponse.a2uiComponents().isEmpty()) {
            metadata.put("a2uiComponents", agentResponse.a2uiComponents());
        }
        return metadata;
    }

    /** 回写 turn 失败态；若当前请求没有 turn 上下文，则静默跳过。 */
    private void markTurnFailed(@Nullable String sessionId,
                                @Nullable String turnId,
                                int errorCode,
                                String errorMessage) {
        if (sessionId == null || sessionId.isBlank() || turnId == null || turnId.isBlank()) {
            return;
        }
        if (chatTurnService == null) {
            return;
        }
        try {
            chatTurnService.markFailed(sessionId, turnId, null, errorCode, errorMessage);
        } catch (Exception e) {
            log.warn("回写 turn 失败状态失败: sessionId={}, turnId={}, error={}",
                    sessionId, turnId, e.getMessage());
        }
    }

    @Override
    public int order() {
        return properties.middleware().execution().order();
    }

    @Override
    public String name() {
        return "execution";
    }

    @Override
    public boolean enabled() {
        return properties.middleware().execution().enabled();
    }
}
