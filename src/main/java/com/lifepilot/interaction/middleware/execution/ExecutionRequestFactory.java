package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.InteractionTraceHeaders;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.SessionConfigKeys;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.llm.multimodal.MediaContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 执行链路请求构建器。
 *
 * <p>负责从网关消息解析 turn/action、预算、附件和会话配置，避免中间件继续承担请求装配职责。
 *
 * @author zsg
 * @since 2026-03-25
 */
public final class ExecutionRequestFactory {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRequestFactory.class);

    private final AgentConfigProperties agentConfigProperties;
    private final ChatSessionRepository chatSessionRepository;

    public ExecutionRequestFactory(AgentConfigProperties agentConfigProperties,
                                   ChatSessionRepository chatSessionRepository) {
        this.agentConfigProperties = agentConfigProperties;
        this.chatSessionRepository = chatSessionRepository;
    }

    /**
     * 将网关层消息统一转换为 AgentRequest。
     *
     * <p>这里会收口 turn/action、附件、多模态输入、预算和会话偏好，确保后续编排层拿到的是完整请求。
     */
    public AgentRequest build(GatewayMessage message) {
        Map<String, Object> sessionConfig = getSessionConfig(message.sessionId());
        ChatTurnAction action = resolveTurnAction(message);
        InteractionSource interactionSource = resolveInteractionSource(message);
        return new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                interactionSource,
                message.userId(),
                resolveTurnId(message),
                action,
                AgentTaskMode.AUTO,
                null,
                resolveSessionBudget(sessionConfig),
                null,
                0,
                resolvePreferredProvider(message, sessionConfig),
                null,
                buildMediaContents(message),
                resolveTemperature(sessionConfig),
                action.toResumePolicy()
        );
    }

    private InteractionSource resolveInteractionSource(GatewayMessage message) {
        InteractionSource tracedSource = resolveTraceHeaderSource(message);
        if (tracedSource != null) {
            return tracedSource;
        }
        return InteractionSource.legacy(message.channelType().value(), message.sessionId());
    }

    @Nullable
    private InteractionSource resolveTraceHeaderSource(GatewayMessage message) {
        Map<String, String> traceHeaders = message.traceHeaders();
        String rawSourceKind = normalizeText(traceHeaders.get(InteractionTraceHeaders.SOURCE_KIND));
        String sourceId = normalizeText(traceHeaders.get(InteractionTraceHeaders.SOURCE_ID));
        String platform = normalizeText(traceHeaders.get(InteractionTraceHeaders.CHANNEL_PLATFORM));
        String instanceId = normalizeText(traceHeaders.get(InteractionTraceHeaders.CHANNEL_INSTANCE_ID));
        if (rawSourceKind == null && platform == null && instanceId == null) {
            return null;
        }
        SourceKind sourceKind = resolveSourceKind(rawSourceKind);
        String effectiveSourceId = sourceId != null ? sourceId : instanceId;
        if (sourceKind == SourceKind.CHANNEL && platform != null && instanceId != null) {
            return InteractionSource.channel(
                    effectiveSourceId != null ? effectiveSourceId : instanceId,
                    platform,
                    instanceId
            );
        }
        if (effectiveSourceId == null) {
            return null;
        }
        return switch (sourceKind) {
            case CHANNEL -> InteractionSource.channel(message.channelType().value(), effectiveSourceId);
            case WORKFLOW -> InteractionSource.workflow(effectiveSourceId);
            case CRON -> InteractionSource.cron(effectiveSourceId);
            case HEARTBEAT -> InteractionSource.heartbeat(effectiveSourceId);
            case SYSTEM -> InteractionSource.system(effectiveSourceId);
        };
    }

    private SourceKind resolveSourceKind(@Nullable String rawSourceKind) {
        if (rawSourceKind == null || rawSourceKind.isBlank()) {
            return SourceKind.SYSTEM;
        }
        try {
            return SourceKind.valueOf(rawSourceKind);
        } catch (IllegalArgumentException ignored) {
            return SourceKind.SYSTEM;
        }
    }

    @Nullable
    private String normalizeText(@Nullable String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /**
     * 解析当前消息归属的 turnId。
     *
     * <p>Web 聊天优先使用前端传入的 turnId；其他通道退化到 messageId，保证请求始终能绑定到稳定标识。
     */
    @Nullable
    public String resolveTurnId(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata) {
            return SessionConfigKeys.normalizeString(webMetadata.turnId());
        }
        return SessionConfigKeys.normalizeString(message.messageId());
    }

    /** 将网关附件转换为 Agent 统一使用的多模态媒体对象。 */
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

    /** 按“请求显式指定优先，会话默认次之”的顺序解析模型服务提供方。 */
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

    /** 解析当前 turn 的动作语义，默认视为普通发送。 */
    private ChatTurnAction resolveTurnAction(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata
                && webMetadata.action() != null) {
            return webMetadata.action();
        }
        return ChatTurnAction.SEND;
    }

    /** 读取会话级 temperature，并过滤掉非法负值。 */
    @Nullable
    private Double resolveTemperature(Map<String, Object> sessionConfig) {
        Double temperature = SessionConfigKeys.getDouble(sessionConfig, SessionConfigKeys.TEMPERATURE);
        return temperature != null && temperature >= 0 ? temperature : null;
    }

    /**
     * 根据会话配置覆盖默认预算。
     *
     * <p>仅在会话显式配置了 step 或 duration 上限时生成新预算，否则返回 null，表示沿用系统默认值。
     */
    @Nullable
    private Budget resolveSessionBudget(Map<String, Object> sessionConfig) {
        Integer maxSteps = positiveInteger(sessionConfig, SessionConfigKeys.MAX_STEPS);
        Integer maxDurationSeconds = positiveInteger(sessionConfig, SessionConfigKeys.MAX_DURATION_SECONDS);
        if (maxSteps == null && maxDurationSeconds == null) {
            return null;
        }

        var builder = Budget.fromConfig(agentConfigProperties.getBudget()).toBuilder();
        if (maxSteps != null) {
            builder.maxSteps(maxSteps);
        }
        if (maxDurationSeconds != null) {
            builder.maxDuration(Duration.ofSeconds(maxDurationSeconds));
        }
        return builder.build();
    }

    /** 安全读取会话配置；读取失败时回退为空配置。 */
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

    /** 提取严格大于 0 的整型配置，避免把 0 或负值误当成有效上限。 */
    @Nullable
    private Integer positiveInteger(Map<String, Object> sessionConfig, String key) {
        Integer value = SessionConfigKeys.getInteger(sessionConfig, key);
        return value != null && value > 0 ? value : null;
    }
}
