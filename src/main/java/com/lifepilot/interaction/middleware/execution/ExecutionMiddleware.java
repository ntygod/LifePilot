package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.CancellationToken;
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
import com.lifepilot.interaction.web.model.SessionConfigKeys;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.multimodal.MediaContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ExecutionMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMiddleware.class);
    private static final String DEFAULT_MODEL_ID = "agent";

    private final ReactAgentLoop reactAgentLoop;
    private final GatewayProperties properties;
    private final ChatSessionRepository chatSessionRepository;
    @Nullable
    private final SseSessionManager sseSessionManager;

    public ExecutionMiddleware(ReactAgentLoop reactAgentLoop,
                               GatewayProperties properties,
                               ChatSessionRepository chatSessionRepository,
                               @Nullable SseSessionManager sseSessionManager) {
        this.reactAgentLoop = reactAgentLoop;
        this.properties = properties;
        this.chatSessionRepository = chatSessionRepository;
        this.sseSessionManager = sseSessionManager;
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        if (isStreamingRequest(message)) {
            return processStreaming(message);
        }
        return processSync(message, chain);
    }

    private boolean isStreamingRequest(GatewayMessage message) {
        return message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata
                && webMetadata.acceptsSse();
    }

    private GatewayResponse processSync(GatewayMessage message, MiddlewareChain chain) {
        AgentRequest agentRequest = toAgentRequest(message);
        CompletableFuture<AgentResponse> future = null;

        try {
            future = CompletableFuture.supplyAsync(() -> reactAgentLoop.run(agentRequest));
            int timeoutSeconds = properties.execution().timeoutSeconds();
            AgentResponse agentResponse = future.get(timeoutSeconds, TimeUnit.SECONDS);

            TokenUsage tokenUsage = agentResponse.tokenUsage() != null
                    ? agentResponse.tokenUsage()
                    : new TokenUsage(0, agentResponse.tokensUsed(), agentResponse.tokensUsed(), DEFAULT_MODEL_ID);
            chain.context().set(MiddlewareContext.KEY_AGENT_RESPONSE, agentResponse);
            chain.context().set(MiddlewareContext.KEY_TOKEN_USAGE, tokenUsage);

            Map<String, Object> metadata = new LinkedHashMap<>();
            if (agentResponse.traceId() != null && !agentResponse.traceId().isBlank()) {
                metadata.put("traceId", agentResponse.traceId());
            }
            if (agentResponse.a2uiComponents() != null && !agentResponse.a2uiComponents().isEmpty()) {
                metadata.put("a2uiComponents", agentResponse.a2uiComponents());
            }

            return GatewayResponse.success(message.channelType(), new ResponseContent.TextContent(agentResponse.content()))
                    .toBuilder()
                    .responseId(agentResponse.messageId())
                    .tokenUsage(tokenUsage)
                    .metadata(metadata)
                    .build();
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("Agent 执行超时: messageId={}, timeout={}s",
                    message.messageId(), properties.execution().timeoutSeconds());
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

    private GatewayResponse processStreaming(GatewayMessage message) {
        SseSessionManager manager = sseSessionManager;
        if (manager == null) {
            log.warn("流式请求被拒绝，SseSessionManager 不可用: messageId={}", message.messageId());
            return GatewayResponse.error(message.channelType(), "流式响应暂不可用", 503);
        }

        String streamId = UUID.randomUUID().toString();
        manager.createEmitter(streamId);

        GatewayResponse response = GatewayResponse.success(
                message.channelType(),
                new ResponseContent.StreamingContent(streamId)
        );

        CancellationToken cancellationToken = new CancellationToken();
        manager.registerCancellationToken(streamId, cancellationToken);

        int timeoutSeconds = properties.execution().timeoutSeconds();
        AgentRequest agentRequest = toAgentRequest(message);

        CompletableFuture.runAsync(() -> runStreaming(agentRequest, message, streamId, manager, cancellationToken))
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                        log.error("流式处理超时: messageId={}, streamId={}, timeout={}s",
                                message.messageId(), streamId, timeoutSeconds);
                        cancellationToken.cancel();

                        Map<String, Object> errorData = new LinkedHashMap<>();
                        errorData.put("code", 504);
                        errorData.put("message", "处理超时，请稍后重试");
                        manager.sendEvent(streamId, SseEventType.ERROR, errorData);
                        manager.closeEmitter(streamId);
                    }
                    return null;
                });

        return response;
    }

    private void runStreaming(AgentRequest agentRequest,
                              GatewayMessage message,
                              String streamId,
                              SseSessionManager manager,
                              CancellationToken cancellationToken) {
        try {
            reactAgentLoop.runStreaming(agentRequest, streamId, manager, cancellationToken);
        } catch (Exception e) {
            log.error("流式处理异常: messageId={}, streamId={}", message.messageId(), streamId, e);
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("code", 500);
            errorData.put("message", "流式处理失败: " + e.getMessage());
            manager.sendEvent(streamId, SseEventType.ERROR, errorData);
            manager.closeEmitter(streamId);
        }
    }

    private AgentRequest toAgentRequest(GatewayMessage message) {
        // 读取会话级 maxTokens 配置，构建 Budget
        var budget = resolveSessionBudget(message.sessionId());
        return new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                message.channelType().value(),
                null,
                budget,
                null,
                0,
                resolvePreferredProvider(message),
                null,
                buildMediaContents(message),
                resolveTemperature(message.sessionId())
        );
    }

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

    @Nullable
    private String resolvePreferredProvider(GatewayMessage message) {
        String requestPreferredProvider = extractPreferredProvider(message);
        if (requestPreferredProvider != null) {
            return requestPreferredProvider;
        }

        if (message.sessionId() == null || message.sessionId().isBlank()) {
            return null;
        }

        Map<String, Object> config = chatSessionRepository.getConfig(message.sessionId());
        return SessionConfigKeys.resolvePreferredProviderId(config);
    }

    @Nullable
    private String extractPreferredProvider(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata) {
            return SessionConfigKeys.normalizeString(webMetadata.preferredProvider());
        }
        return null;
    }

    /**
     * 从会话配置读取 temperature。无配置时返回 null（使用模型默认值）。
     */
    @Nullable
    private Double resolveTemperature(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> config = chatSessionRepository.getConfig(sessionId);
            if (config == null || !config.containsKey(SessionConfigKeys.TEMPERATURE)) {
                return null;
            }
            Object tempObj = config.get(SessionConfigKeys.TEMPERATURE);
            if (tempObj instanceof Number num) {
                double val = num.doubleValue();
                return val >= 0 ? val : null;
            }
        } catch (Exception e) {
            log.debug("读取会话 temperature 配置失败，使用默认值: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
        return null;
    }

    /**
     * 从会话配置读取 maxTokens，构建 Budget。无配置时返回 null（使用默认值）。
     */
    @Nullable
    private com.lifepilot.agent.model.Budget resolveSessionBudget(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> config = chatSessionRepository.getConfig(sessionId);
            if (config == null || !config.containsKey(SessionConfigKeys.MAX_TOKENS)) {
                return null;
            }
            Object maxTokensObj = config.get(SessionConfigKeys.MAX_TOKENS);
            if (maxTokensObj instanceof Number num && num.intValue() > 0) {
                return com.lifepilot.agent.model.Budget.defaultBudget()
                        .toBuilder().maxTokens(num.intValue()).build();
            }
        } catch (Exception e) {
            log.debug("读取会话 maxTokens 配置失败，使用默认值: sessionId={}, error={}",
                    sessionId, e.getMessage());
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
