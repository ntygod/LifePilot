package com.lifepilot.interaction.middleware.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final AgentLoop agentLoop;
    private final GatewayProperties properties;
    private final SseSessionManager sseSessionManager;

    public ExecutionMiddleware(AgentLoop agentLoop,
                               GatewayProperties properties,
                               SseSessionManager sseSessionManager,
                               AttachmentRepository attachmentRepository) {
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.sseSessionManager = sseSessionManager;
    }

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

    /**
     * 同步处理消息（非流式）。
     */
    private GatewayResponse processSync(GatewayMessage message, MiddlewareChain chain) {
        // 1. GatewayMessage → AgentRequest
        List<MediaContent> mediaContents = buildMediaContents(message);
        var agentRequest = new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                message.channelType().value(),
                null,
                null,
                null,
                0,
                null,
                null,
                mediaContents
        );

        // 2. 带超时调用 AgentLoop.run()
        try {
            var future = CompletableFuture.supplyAsync(() -> agentLoop.run(agentRequest));
            int timeout = properties.execution().timeoutSeconds();
            var agentResponse = future.get(timeout, TimeUnit.SECONDS);

            // 3. AgentResponse → GatewayResponse，构建 TokenUsage
            var tokenUsage = new TokenUsage(0, 0, agentResponse.tokensUsed(), "agent");
            chain.context().set(MiddlewareContext.KEY_AGENT_RESPONSE, agentResponse);
            chain.context().set(MiddlewareContext.KEY_TOKEN_USAGE, tokenUsage);

            return GatewayResponse.success(message.channelType(),
                            new ResponseContent.TextContent(agentResponse.content()))
                    .toBuilder().tokenUsage(tokenUsage).build();

        } catch (TimeoutException e) {
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

        // 2. 返回 StreamingContent，让 Controller 创建 SseEmitter
        var response = GatewayResponse.success(message.channelType(),
                new ResponseContent.StreamingContent(streamId));

        // 3. 在后台异步执行 AgentLoop，并通过 SSE 发送事件
        CompletableFuture.runAsync(() -> {
            try {
                List<MediaContent> mediaContents = buildMediaContents(message);
                var agentRequest = new AgentRequest(
                        message.contentAsText(),
                        message.sessionId(),
                        message.channelType().value(),
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        mediaContents
                );

                // 调用流式版本的 AgentLoop
                agentLoop.runStreaming(agentRequest, streamId, sseSessionManager);

            } catch (Exception e) {
                log.error("流式处理异常: messageId={}, streamId={}", message.messageId(), streamId, e);
                // 发送错误事件
                sseSessionManager.sendEvent(streamId, SseEventType.ERROR, java.util.Map.of(
                        "code", 500,
                        "message", "流式处理失败: " + e.getMessage()
                ));
                sseSessionManager.closeEmitter(streamId);
            }
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
