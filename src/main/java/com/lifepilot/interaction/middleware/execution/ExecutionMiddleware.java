package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ResumePolicy;
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

    private final AgentOrchestrator agentOrchestrator;
    private final AgentConfigProperties agentConfigProperties;
    private final GatewayProperties properties;
    private final ChatSessionRepository chatSessionRepository;
    @Nullable
    private final SseSessionManager sseSessionManager;

    public ExecutionMiddleware(AgentOrchestrator agentOrchestrator,
                               AgentConfigProperties agentConfigProperties,
                               GatewayProperties properties,
                               ChatSessionRepository chatSessionRepository,
                               @Nullable SseSessionManager sseSessionManager) {
        this.agentOrchestrator = agentOrchestrator;
        this.agentConfigProperties = agentConfigProperties;
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
            future = CompletableFuture.supplyAsync(() -> agentOrchestrator.run(agentRequest));
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
            metadata.put("completionMode", agentResponse.completionMode().name());
            if (agentResponse.resumedFromTraceId() != null && !agentResponse.resumedFromTraceId().isBlank()) {
                metadata.put("resumedFromTraceId", agentResponse.resumedFromTraceId());
            }
            if (agentResponse.a2uiComponents() != null && !agentResponse.a2uiComponents().isEmpty()) {
                metadata.put("a2uiComponents", agentResponse.a2uiComponents());
            }

            if (agentResponse.completionMode() == CompletionMode.NORMAL
                    && agentResponse.terminationReason() != null
                    && !agentResponse.terminationReason().isBlank()) {
                return GatewayResponse.error(message.channelType(), agentResponse.content(), 500)
                        .toBuilder()
                        .metadata(metadata)
                        .build();
            }

            return GatewayResponse.success(message.channelType(), new ResponseContent.TextContent(agentResponse.content()))
                    .toBuilder()
                    .responseId(agentResponse.assistantEntryId())
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
            agentOrchestrator.runStreaming(agentRequest, streamId, manager, cancellationToken);
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
        Map<String, Object> sessionConfig = getSessionConfig(message.sessionId());
        return new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                message.channelType().value(),
                null,
                resolveSessionBudget(sessionConfig),
                null,
                0,
                resolvePreferredProvider(message, sessionConfig),
                null,
                buildMediaContents(message),
                resolveTemperature(sessionConfig),
                resolveResumePolicy(message)
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
    private String resolvePreferredProvider(GatewayMessage message, Map<String, Object> sessionConfig) {
        String requestPreferredProvider = extractPreferredProvider(message);
        if (requestPreferredProvider != null) {
            return requestPreferredProvider;
        }
        return SessionConfigKeys.resolvePreferredProviderId(sessionConfig);
    }

    @Nullable
    private String extractPreferredProvider(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata) {
            return SessionConfigKeys.normalizeString(webMetadata.preferredProvider());
        }
        return null;
    }

    private ResumePolicy resolveResumePolicy(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata
                && webMetadata.resumePolicy() != null) {
            return webMetadata.resumePolicy();
        }
        return ResumePolicy.AUTO;
    }

    /**
     * 从会话配置读取 temperature。无配置时返回 null（使用模型默认值）。
     */
    @Nullable
    private Double resolveTemperature(Map<String, Object> sessionConfig) {
        Double temperature = SessionConfigKeys.getDouble(sessionConfig, SessionConfigKeys.TEMPERATURE);
        return temperature != null && temperature >= 0 ? temperature : null;
    }

    /**
     * 从会话配置读取三维预算覆盖。
     */
    @Nullable
    private Budget resolveSessionBudget(Map<String, Object> sessionConfig) {
        Integer maxTokens = positiveInteger(sessionConfig, SessionConfigKeys.MAX_TOKENS);
        Integer maxSteps = positiveInteger(sessionConfig, SessionConfigKeys.MAX_STEPS);
        Integer maxDurationSeconds = positiveInteger(sessionConfig, SessionConfigKeys.MAX_DURATION_SECONDS);
        if (maxTokens == null && maxSteps == null && maxDurationSeconds == null) {
            return null;
        }

        var builder = Budget.fromConfig(agentConfigProperties.getBudget()).toBuilder();
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        if (maxSteps != null) {
            builder.maxSteps(maxSteps);
        }
        if (maxDurationSeconds != null) {
            builder.maxDuration(java.time.Duration.ofSeconds(maxDurationSeconds));
        }
        return builder.build();
    }

    private Map<String, Object> getSessionConfig(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        try {
            return chatSessionRepository.getConfig(sessionId);
        } catch (Exception e) {
            log.debug("读取会话配置失败，使用默认值: sessionId={}, error={}", sessionId, e.getMessage());
            return Map.of();
        }
    }

    @Nullable
    private Integer positiveInteger(Map<String, Object> sessionConfig, String key) {
        Integer value = SessionConfigKeys.getInteger(sessionConfig, key);
        return value != null && value > 0 ? value : null;
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
