package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.InteractionTraceHeaders;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventResponse;
import com.lifepilot.interaction.middleware.auth.TrustLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 渠道运行时入站服务。
 *
 * <p>负责将 connector 上报的统一事件转换为 {@link GatewayMessage}，并收口到统一网关。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelRuntimeIngressService {

    private static final Logger log = LoggerFactory.getLogger(ChannelRuntimeIngressService.class);

    private final ChannelInstanceService channelInstanceService;
    private final ChannelIngressService channelIngressService;
    private final ConnectorRuntimeManager connectorRuntimeManager;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final ChannelDeliveryDispatcher channelDeliveryDispatcher;

    public ChannelRuntimeIngressService(ChannelInstanceService channelInstanceService,
                                        ChannelIngressService channelIngressService,
                                        ConnectorRuntimeManager connectorRuntimeManager,
                                        ChannelInstanceEventService channelInstanceEventService,
                                        ChannelDeliveryDispatcher channelDeliveryDispatcher) {
        this.channelInstanceService = channelInstanceService;
        this.channelIngressService = channelIngressService;
        this.connectorRuntimeManager = connectorRuntimeManager;
        this.channelInstanceEventService = channelInstanceEventService;
        this.channelDeliveryDispatcher = channelDeliveryDispatcher;
    }

    public ChannelRuntimeEventResponse processEvent(String instanceId, ChannelRuntimeEventRequest request) {
        ChannelInstance instance = requireActiveInstance(instanceId);
        try {
            GatewayMessage message = toGatewayMessage(instance, request);
            GatewayResponse response = channelIngressService.submitSync(message);
            connectorRuntimeManager.markHeartbeat(instanceId);
            channelInstanceEventService.record(
                    instanceId,
                    "INGRESS_EVENT_PROCESSED",
                    "connector 入站事件处理完成",
                    buildProcessedPayload(request, response)
            );
            return channelDeliveryDispatcher.buildEventResponse(instance, request, response);
        } catch (RuntimeException e) {
            connectorRuntimeManager.markError(instanceId, e.getMessage());
            channelInstanceEventService.record(
                    instanceId,
                    "INGRESS_EVENT_FAILED",
                    e.getMessage(),
                    buildFailedPayload(request, e)
            );
            log.warn("渠道运行时事件处理失败: instanceId={}, error={}", instanceId, e.getMessage());
            throw e;
        }
    }

    public ChannelInstance recordHeartbeat(String instanceId) {
        requireActiveInstance(instanceId);
        return connectorRuntimeManager.markHeartbeat(instanceId);
    }

    private ChannelInstance requireActiveInstance(String instanceId) {
        ChannelInstance instance = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        if (!instance.enabled()) {
            throw new IllegalArgumentException("渠道实例未启用: " + instanceId);
        }
        if (instance.status() == ChannelInstanceStatus.STOPPED) {
            throw new IllegalArgumentException("渠道实例未运行: " + instanceId);
        }
        return instance;
    }

    private GatewayMessage toGatewayMessage(ChannelInstance instance, ChannelRuntimeEventRequest request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (request.content() == null) {
            throw new IllegalArgumentException("content 不能为空");
        }

        String messageId = firstNonBlank(request.messageId(), request.eventId(), UUID.randomUUID().toString());
        String sessionId = firstNonBlank(request.sessionId(), instance.instanceId() + ":" + request.userId());
        Instant timestamp = request.occurredAt() != null ? request.occurredAt() : Instant.now();

        return GatewayMessage.builder()
                .messageId(messageId)
                .channelType(resolveChannelType(instance.platform()))
                .userId(request.userId().trim())
                .sessionId(sessionId)
                .content(buildContent(request.content()))
                .attachments(buildAttachments(request.attachments()))
                .channelMetadata(null)
                .timestamp(timestamp)
                .traceHeaders(buildTraceHeaders(instance, request))
                .build();
    }

    private MessageContent buildContent(ChannelRuntimeEventRequest.Content content) {
        String type = content.type() != null ? content.type().trim().toLowerCase() : "text";
        return switch (type) {
            case "command" -> buildCommandContent(content);
            case "event" -> buildEventContent(content);
            case "text" -> new MessageContent.TextMessage(requireText(content.text(), "text"));
            default -> throw new IllegalArgumentException("不支持的 connector 内容类型: " + type);
        };
    }

    private MessageContent.CommandMessage buildCommandContent(ChannelRuntimeEventRequest.Content content) {
        String rawText = requireText(content.text(), "command");
        String normalized = rawText.startsWith("/") ? rawText : "/" + rawText;
        return MessageContent.CommandMessage.parse(normalized);
    }

    private MessageContent.EventMessage buildEventContent(ChannelRuntimeEventRequest.Content content) {
        String eventName = content.name() != null && !content.name().isBlank()
                ? content.name().trim()
                : null;
        if (eventName == null) {
            throw new IllegalArgumentException("event 类型消息必须提供 name");
        }
        return new MessageContent.EventMessage(
                eventName,
                content.payload() != null ? content.payload() : Map.of()
        );
    }

    private List<GatewayMessage.Attachment> buildAttachments(List<ChannelRuntimeEventRequest.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<GatewayMessage.Attachment> results = new ArrayList<>(attachments.size());
        for (ChannelRuntimeEventRequest.Attachment attachment : attachments) {
            if (attachment.base64Data() == null || attachment.base64Data().isBlank()) {
                throw new IllegalArgumentException("附件 base64Data 不能为空");
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(attachment.base64Data());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("附件 Base64 解码失败: " + attachment.fileName(), e);
            }
            String attachmentId = attachment.attachmentId() != null && !attachment.attachmentId().isBlank()
                    ? attachment.attachmentId().trim()
                    : UUID.randomUUID().toString();
            String fileName = attachment.fileName() != null && !attachment.fileName().isBlank()
                    ? attachment.fileName().trim()
                    : attachmentId;
            String mimeType = attachment.mimeType() != null && !attachment.mimeType().isBlank()
                    ? attachment.mimeType().trim()
                    : "application/octet-stream";
            long size = attachment.size() > 0 ? attachment.size() : data.length;
            results.add(new GatewayMessage.Attachment(
                    attachmentId,
                    fileName,
                    mimeType,
                    data,
                    size
            ));
        }
        return List.copyOf(results);
    }

    private Map<String, String> buildTraceHeaders(ChannelInstance instance, ChannelRuntimeEventRequest request) {
        DeliveryMode deliveryMode = request.deliveryHints() != null && request.deliveryHints().deliveryMode() != null
                ? request.deliveryHints().deliveryMode()
                : DeliveryMode.ASYNC_PUSH;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InteractionTraceHeaders.DELIVERY_MODE, deliveryMode.name());
        headers.put(InteractionTraceHeaders.SOURCE_KIND, SourceKind.CHANNEL.name());
        headers.put(InteractionTraceHeaders.SOURCE_ID, instance.instanceId());
        headers.put(InteractionTraceHeaders.CHANNEL_PLATFORM, instance.platform());
        headers.put(InteractionTraceHeaders.CHANNEL_INSTANCE_ID, instance.instanceId());
        headers.put(InteractionTraceHeaders.AUTH_USER_ID, request.userId().trim());
        headers.put(InteractionTraceHeaders.AUTH_TRUST_LEVEL, TrustLevel.VERIFIED.name());
        return Map.copyOf(headers);
    }

    private ChannelType resolveChannelType(String platform) {
        String normalized = platform == null ? "" : platform.trim().toLowerCase();
        return switch (normalized) {
            case "web" -> ChannelType.WEB;
            case "wecom" -> ChannelType.WECOM;
            case "dingtalk" -> ChannelType.DINGTALK;
            case "feishu" -> ChannelType.FEISHU;
            default -> throw new IllegalArgumentException("当前执行链路尚未支持该渠道平台: " + platform);
        };
    }

    private String requireText(String text, String contentType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(contentType + " 类型消息必须提供 text");
        }
        return text.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private Map<String, Object> buildProcessedPayload(ChannelRuntimeEventRequest request,
                                                      GatewayResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotBlank(payload, "eventId", request.eventId());
        putIfNotBlank(payload, "messageId", request.messageId());
        putIfNotBlank(payload, "sessionId", request.sessionId());
        putIfNotBlank(payload, "userId", request.userId());
        payload.put("contentType", request.content() != null ? request.content().type() : "text");
        putIfNotBlank(payload, "responseId", response.responseId());
        payload.put("statusCode", response.statusCode());
        payload.put("deliveryMode", request.deliveryHints() != null && request.deliveryHints().deliveryMode() != null
                ? request.deliveryHints().deliveryMode().name()
                : DeliveryMode.ASYNC_PUSH.name());
        payload.put("attachmentCount", request.attachments() != null ? request.attachments().size() : 0);
        return Map.copyOf(payload);
    }

    private Map<String, Object> buildFailedPayload(ChannelRuntimeEventRequest request, RuntimeException e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotBlank(payload, "eventId", request.eventId());
        putIfNotBlank(payload, "messageId", request.messageId());
        putIfNotBlank(payload, "sessionId", request.sessionId());
        putIfNotBlank(payload, "userId", request.userId());
        payload.put("contentType", request.content() != null ? request.content().type() : "text");
        payload.put("errorType", e.getClass().getSimpleName());
        return Map.copyOf(payload);
    }

    private void putIfNotBlank(Map<String, Object> payload, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value.trim());
        }
    }
}
