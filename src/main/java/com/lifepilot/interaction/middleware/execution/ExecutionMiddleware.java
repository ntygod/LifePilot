package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.observability.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 执行中间件，将 GatewayMessage 转换为 AgentRequest 并调用 AgentLoop。
 *
 * <p>负责 GatewayMessage → AgentRequest 的转换、带超时的 AgentLoop 调用、
 * AgentResponse → GatewayResponse 的转换，以及异常处理（超时、执行异常、中断）。
 *
 * <p>支持流式响应：当消息元数据中 acceptsSse=true 时，返回 StreamingContent，
 * 并在后台异步执行 AgentLoop，通过 SSE 发送 token 事件。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ExecutionMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMiddleware.class);

    /** 当 TraceContext 中没有 LlmCallStep 时的默认 modelId。 */
    private static final String DEFAULT_MODEL_ID = "agent";

    private final AgentLoop agentLoop;
    private final GatewayProperties properties;
    private final AttachmentRepository attachmentRepository;
    private final SseSessionManager sseSessionManager;
    private final TraceRecorder traceRecorder;

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        // 检查是否为流式请求
        boolean isStreaming = message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata
                && webMetadata.acceptsSse();

        if (isStreaming) {
            return processStreaming(message, chain);
        } else {
            return processSync(message, chain);
        }
    }

    public ExecutionMiddleware(AgentLoop agentLoop,
                               GatewayProperties properties,
                               AttachmentRepository attachmentRepository,
                               SseSessionManager sseSessionManager,
                               TraceRecorder traceRecorder) {
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.attachmentRepository = attachmentRepository;
        this.sseSessionManager = sseSessionManager;
        this.traceRecorder = traceRecorder;
    }

    private GatewayResponse processSync(GatewayMessage message, MiddlewareChain chain) {
        // 1. GatewayMessage → AgentRequest，从 WebMetadata 提取会话级模型配置
        List<MediaContent> mediaContents = buildMediaContents(message);
        String preferredProvider = extractPreferredProvider(message);
        var agentRequest = new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                message.channelType().value(),
                null,
                null,
                null,
                0,
                preferredProvider,
                null,
                mediaContents
        );

        // 2. 带超时调用 AgentLoop.run()
        CompletableFuture<AgentResponse> future = null;
        try {
            future = CompletableFuture.supplyAsync(() -> agentLoop.run(agentRequest));
            int timeout = properties.execution().timeoutSeconds();
            var agentResponse = future.get(timeout, TimeUnit.SECONDS);

            // 3. AgentResponse → GatewayResponse，构建 TokenUsage
            // 直接从 AgentResponse 获取 token 统计（Fix 10）
            // AgentResponse.tokensUsed() 已由 AgentLoop 通过 TraceContext 聚合（Fix 11），无需依赖 ThreadLocal
            int totalTokens = agentResponse.tokensUsed();
            var tokenUsage = new TokenUsage(0, totalTokens, totalTokens, DEFAULT_MODEL_ID);
            chain.context().set(MiddlewareContext.KEY_AGENT_RESPONSE, agentResponse);
            chain.context().set(MiddlewareContext.KEY_TOKEN_USAGE, tokenUsage);
            var metadata = new LinkedHashMap<String, Object>();
            if (agentResponse.traceId() != null && !agentResponse.traceId().isBlank()) {
                metadata.put("traceId", agentResponse.traceId());
            }
            if (agentResponse.a2uiComponents() != null && !agentResponse.a2uiComponents().isEmpty()) {
                metadata.put("a2uiComponents", agentResponse.a2uiComponents());
            }
            return GatewayResponse.success(message.channelType(),
                            new ResponseContent.TextContent(agentResponse.content()))
                    .toBuilder()
                    .responseId(agentResponse.messageId())
                    .tokenUsage(tokenUsage)
                    .metadata(metadata)
                    .build();

        } catch (TimeoutException e) {
            // 超时后取消底层任务，配合 coreLoop 的中断检查点终止 AgentLoop
            if (future != null) {
                future.cancel(true);
            }
            log.warn("Agent 执行超时: messageId={}, timeout={}s", message.messageId(),
                    properties.execution().timeoutSeconds());
            return GatewayResponse.error(message.channelType(), "请求处理超时", 504);
        } catch (ExecutionException e) {
            log.error("Agent 执行异常: messageId={}", message.messageId(), e.getCause());
            return GatewayResponse.error(message.channelType(), "处理请求时发生内部错误", 500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Agent 执行被中断: messageId={}", message.messageId());
            return GatewayResponse.error(message.channelType(), "请求被中断", 500);
        }
    }

    /**
     * 流式处理消息（异步执行，通过 SSE 发送 token 事件）。
     */
    private GatewayResponse processStreaming(GatewayMessage message, MiddlewareChain chain) {
        // 1. 生成 streamId
        String streamId = UUID.randomUUID().toString();
        log.debug("开始流式处理: messageId={}, streamId={}", message.messageId(), streamId);

        // 2. 先注册 SseEmitter，确保异步任务发送事件时 emitter 已存在（避免竞态丢事件）
        sseSessionManager.createEmitter(streamId);

        // 3. 返回 StreamingContent，Controller 通过 streamId 获取已注册的 emitter
        var response = GatewayResponse.success(message.channelType(),
                new ResponseContent.StreamingContent(streamId));

        // 4. 创建取消信号令牌，超时/断开时触发 cancel() 通知 coreLoop 停止
        var cancellationToken = new com.lifepilot.agent.CancellationToken();

        // 5. 注册取消信号令牌到 SseSessionManager，SSE 回调触发时自动传递取消信号
        sseSessionManager.registerCancellationToken(streamId, cancellationToken);

        // 6. 在后台异步执行 AgentLoop，并通过 SSE 发送事件
        int timeoutSeconds = properties.execution().timeoutSeconds();
        CompletableFuture.runAsync(() -> {
            try {
                List<MediaContent> mediaContents = buildMediaContents(message);
                String streamPreferredProvider = extractPreferredProvider(message);
                var agentRequest = new AgentRequest(
                        message.contentAsText(),
                        message.sessionId(),
                        message.channelType().value(),
                        null,
                        null,
                        null,
                        0,
                        streamPreferredProvider,
                        null,
                        mediaContents
                );

                // 调用流式版本的 AgentLoop，传入取消信号令牌
                agentLoop.runStreaming(agentRequest, streamId, sseSessionManager, cancellationToken);

            } catch (Exception e) {
                log.error("流式处理异常: messageId={}, streamId={}", message.messageId(), streamId, e);
                var errorData = new java.util.HashMap<String, Object>();
                errorData.put("code", 500);
                errorData.put("message", "流式处理失败: " + e.getMessage());
                sseSessionManager.sendEvent(streamId, SseEventType.ERROR, errorData);
                sseSessionManager.closeEmitter(streamId);
            }
        }).orTimeout(timeoutSeconds, TimeUnit.SECONDS)
          .exceptionally(ex -> {
              if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                  log.error("流式处理超时: messageId={}, streamId={}, timeout={}s",
                          message.messageId(), streamId, timeoutSeconds);
                  // 超时后通过 CancellationToken 通知 coreLoop 停止执行
                  cancellationToken.cancel();
                  var errorData = new java.util.HashMap<String, Object>();
                  errorData.put("code", 504);
                  errorData.put("message", "处理超时，请稍后重试");
                  sseSessionManager.sendEvent(streamId, SseEventType.ERROR, errorData);
                  sseSessionManager.closeEmitter(streamId);
              }
              return null;
          });

        return response;
    }

    /**
     * 将 GatewayMessage 中的附件转换为多模态 MediaContent 列表。
     *
     * <p>Phase 2：当前仅处理图片等视觉媒体，音频/文件在后续 Phase 中接入独立管线。</p>
     *
     * @param message 网关消息
     * @return 媒体内容列表（无附件时返回空列表）
     */
    private List<MediaContent> buildMediaContents(GatewayMessage message) {
        var attachments = message.attachments();
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(att -> new MediaContent(
                        att.attachmentId(),
                        att.mimeType(),
                        att.data(),
                        att.fileName(),
                        att.size(),
                        Map.of()
                ))
                .toList();
    }

    /**
     * 从 GatewayMessage 的 channelMetadata 中提取会话级偏好 Provider。
     *
     * @param message 网关消息
     * @return preferredProvider，若未携带则返回 null
     */
    private String extractPreferredProvider(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMeta) {
            return webMeta.preferredProvider();
        }
        return null;
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
