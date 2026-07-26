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
import java.util.Locale;
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
    private static final int CONTROL_PREFIX_CHARS = 96;
    private static final List<String> NO_TOOL_CONTROL_PHRASES = List.of(
            "不用工具",
            "不要用工具",
            "不要调用工具",
            "别调用工具",
            "不调用工具",
            "不要使用工具",
            "别使用工具",
            "无需工具",
            "no tools",
            "without tools",
            "skip tools"
    );
    private static final List<String> DIRECT_ANSWER_CONTROL_PHRASES = List.of(
            "直接回答",
            "请直接回答",
            "请你直接回答",
            "只回答",
            "只要答案",
            "answer directly",
            "direct answer"
    );
    private static final List<String> NO_WEB_CONTROL_PHRASES = List.of(
            "不要联网",
            "不联网",
            "不用联网",
            "别联网",
            "不要上网",
            "不用上网",
            "不要搜索网页",
            "不用搜索网页",
            "不要查网上",
            "只用本地",
            "只用已有资料",
            "offline",
            "no web",
            "without web",
            "no internet",
            "without internet",
            "do not search"
    );
    private static final List<String> NO_WEB_TOOL_IDS = List.of("web", "browser");

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
     *
     * <p><b>单轮 override 语义</b>：若 {@link ChannelMetadata.WebMetadata#singleTurnOverride()}
     * 非空，其字段优先于 {@code session_store.config_json}。override 只影响本次 turn 的
     * AgentRequest 构造，不写入持久化配置，取代旧版"发送前 PATCH 再恢复"双 PATCH race 实现。</p>
     */
    public AgentRequest build(GatewayMessage message) {
        Map<String, Object> sessionConfig = getSessionConfig(message.sessionId());
        com.lifepilot.interaction.web.model.SessionConfigOverride override = extractSingleTurnOverride(message);
        ChatTurnAction action = resolveTurnAction(message);
        InteractionSource interactionSource = resolveInteractionSource(message);
        AgentTaskMode taskMode = resolveTaskMode(message.contentAsText(), action);
        List<String> disabledToolIds = resolveDisabledToolIds(message.contentAsText());
        return new AgentRequest(
                message.contentAsText(),
                message.sessionId(),
                interactionSource,
                message.userId(),
                resolveTurnId(message),
                action,
                taskMode,
                null,
                resolveSessionBudget(sessionConfig, override),
                null,
                0,
                resolvePreferredProvider(message, sessionConfig, override),
                null,
                disabledToolIds,
                buildMediaContents(message),
                resolveTemperature(sessionConfig, override),
                action.toResumePolicy(),
                override != null ? override.knowledgeBaseIds() : null,
                override != null ? override.memoryContextMode() : null,
                extractTurnRecoveryContext(message)
        );
    }

    private AgentTaskMode resolveTaskMode(String content, ChatTurnAction action) {
        if (action != ChatTurnAction.SEND) {
            return AgentTaskMode.AUTO;
        }
        String controlSegment = leadingControlSegment(content);
        if (controlSegment.isBlank()) {
            return AgentTaskMode.AUTO;
        }
        String lowerControl = controlSegment.toLowerCase(Locale.ROOT);
        boolean noTools = NO_TOOL_CONTROL_PHRASES.stream().anyMatch(lowerControl::contains);
        boolean directAnswer = DIRECT_ANSWER_CONTROL_PHRASES.stream().anyMatch(lowerControl::contains);
        if (noTools || directAnswer) {
            log.debug("本轮按用户显式控制切换为纯回答模式: noTools={}, directAnswer={}", noTools, directAnswer);
            return AgentTaskMode.ANSWER;
        }
        return AgentTaskMode.AUTO;
    }

    @Nullable
    private List<String> resolveDisabledToolIds(String content) {
        String controlSegment = leadingControlSegment(content);
        if (controlSegment.isBlank()) {
            return null;
        }
        String lowerControl = controlSegment.toLowerCase(Locale.ROOT);
        boolean noWeb = NO_WEB_CONTROL_PHRASES.stream().anyMatch(lowerControl::contains);
        if (!noWeb) {
            return null;
        }
        log.debug("本轮按用户显式控制禁用联网工具: disabledToolIds={}", NO_WEB_TOOL_IDS);
        return NO_WEB_TOOL_IDS;
    }

    private String leadingControlSegment(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        String prefix = firstCodePoints(normalized, CONTROL_PREFIX_CHARS);
        int firstLineEnd = prefix.indexOf('\n');
        String firstLine = firstLineEnd >= 0 ? prefix.substring(0, firstLineEnd) : prefix;
        int delimiter = firstContentDelimiter(firstLine);
        String segment = delimiter >= 0 ? firstLine.substring(0, delimiter) : firstLine;
        return segment.strip();
    }

    private String firstCodePoints(String value, int count) {
        if (count <= 0 || value.isEmpty()) {
            return "";
        }
        int end = 0;
        int remaining = count;
        while (end < value.length() && remaining > 0) {
            end += Character.charCount(value.codePointAt(end));
            remaining--;
        }
        return value.substring(0, end);
    }

    private int firstContentDelimiter(String value) {
        int delimiter = -1;
        for (String candidate : List.of("：", ":", "\n\n")) {
            int index = value.indexOf(candidate);
            if (index >= 0 && (delimiter < 0 || index < delimiter)) {
                delimiter = index;
            }
        }
        return delimiter;
    }

    @Nullable
    private com.lifepilot.interaction.web.model.SessionConfigOverride extractSingleTurnOverride(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata) {
            return webMetadata.singleTurnOverride();
        }
        return null;
    }

    @Nullable
    private Map<String, Object> extractTurnRecoveryContext(GatewayMessage message) {
        if (message.channelMetadata() instanceof ChannelMetadata.WebMetadata webMetadata) {
            return webMetadata.turnRecoveryContext();
        }
        return null;
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

    /**
     * 将网关附件转换为 Agent 统一使用的多模态媒体对象。
     *
     * <p>仅把真正的多模态媒体（image / audio / video）转为 {@link MediaContent}。
     * 文档类附件（pdf / docx / md / txt / csv 等）不应进入多模态路径，否则会触发
     * {@code StreamingCallback.callMultimodalStreaming}，导致 tools 被丢弃、工具调用能力失效。
     * 文档内容应由 Agent 按需调用 {@code file.read(attachmentId=...)} 读取。</p>
     */
    private List<MediaContent> buildMediaContents(GatewayMessage message) {
        var attachments = message.attachments();
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .filter(att -> isMultimodalMedia(att.mimeType()))
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
     * 判断 MIME 是否属于真正的多模态媒体（视觉模型、音频模型能直接消费的类型）。
     *
     * <p>文档类型（application/pdf、application/vnd.openxmlformats-*、text/*）即使可读也不属于多模态媒体 —
     * 它们应通过 {@code file.read(attachmentId=...)} 按需解析，不应挤占多模态推理通道。</p>
     */
    private static boolean isMultimodalMedia(@Nullable String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        return mimeType.startsWith("image/")
                || mimeType.startsWith("audio/")
                || mimeType.startsWith("video/");
    }

    /** 按"单轮 override → 请求显式指定 → 会话默认"的顺序解析模型服务提供方。 */
    @Nullable
    private String resolvePreferredProvider(GatewayMessage message,
                                            Map<String, Object> sessionConfig,
                                            @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride override) {
        if (override != null && override.preferredProviderId() != null && !override.preferredProviderId().isBlank()) {
            return override.preferredProviderId();
        }
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

    /** 读取 temperature：单轮 override → 会话级 → 全局默认。 */
    private double resolveTemperature(Map<String, Object> sessionConfig,
                                      @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride override) {
        if (override != null && override.temperature() != null && override.temperature() >= 0) {
            return override.temperature();
        }
        Double temperature = SessionConfigKeys.getDouble(sessionConfig, SessionConfigKeys.TEMPERATURE);
        if (temperature != null && temperature >= 0) {
            return temperature;
        }
        return agentConfigProperties.getLoop().getDefaultTemperature();
    }

    /**
     * 根据单轮 override 与会话配置覆盖默认预算。
     *
     * <p>优先级：override.maxSteps / maxDurationSeconds > session config > 系统默认。</p>
     */
    @Nullable
    private Budget resolveSessionBudget(Map<String, Object> sessionConfig,
                                        @Nullable com.lifepilot.interaction.web.model.SessionConfigOverride override) {
        Integer maxSteps = override != null && positiveOrNull(override.maxSteps()) != null
                ? override.maxSteps()
                : positiveInteger(sessionConfig, SessionConfigKeys.MAX_STEPS);
        Integer maxDurationSeconds = override != null && positiveOrNull(override.maxDurationSeconds()) != null
                ? override.maxDurationSeconds()
                : positiveInteger(sessionConfig, SessionConfigKeys.MAX_DURATION_SECONDS);
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

    @Nullable
    private static Integer positiveOrNull(@Nullable Integer value) {
        return (value != null && value > 0) ? value : null;
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
