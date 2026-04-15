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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /** 单个附件最大允许大小的默认值：10MB。 */
    private static final long DEFAULT_MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024;
    /** 事件去重缓存默认容量。 */
    private static final int DEFAULT_EVENT_CACHE_MAX_SIZE = 10_000;

    private final ChannelInstanceService channelInstanceService;
    private final ChannelIngressService channelIngressService;
    private final ConnectorRuntimeManager connectorRuntimeManager;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final ChannelDeliveryDispatcher channelDeliveryDispatcher;
    @Nullable
    private final ChannelPermissionApprovalService channelApprovalService;
    @Nullable
    private final ChannelUserMappingCache userMappingCache;
    private final long maxAttachmentSize;
    /** 事件去重缓存（FIFO，超过容量自动淘汰最早条目）。 */
    private final Map<String, Boolean> processedEventIds;

    /** 渠道会话空闲超时（毫秒），超过此时间未收到消息则自动开新会话。默认 30 分钟。 */
    private static final long SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;
    /** 渠道用户级会话跟踪：compositeKey(instanceId:userId) → {sessionId, lastMessageAt}。 */
    private final ConcurrentHashMap<String, ChannelSessionState> channelSessions = new ConcurrentHashMap<>();

    private record ChannelSessionState(String sessionId, long lastMessageAt) {}

    public ChannelRuntimeIngressService(ChannelInstanceService channelInstanceService,
                                        ChannelIngressService channelIngressService,
                                        ConnectorRuntimeManager connectorRuntimeManager,
                                        ChannelInstanceEventService channelInstanceEventService,
                                        ChannelDeliveryDispatcher channelDeliveryDispatcher,
                                        long maxAttachmentSize) {
        this(channelInstanceService, channelIngressService, connectorRuntimeManager,
                channelInstanceEventService, channelDeliveryDispatcher, null, null,
                maxAttachmentSize, DEFAULT_EVENT_CACHE_MAX_SIZE);
    }

    public ChannelRuntimeIngressService(ChannelInstanceService channelInstanceService,
                                        ChannelIngressService channelIngressService,
                                        ConnectorRuntimeManager connectorRuntimeManager,
                                        ChannelInstanceEventService channelInstanceEventService,
                                        ChannelDeliveryDispatcher channelDeliveryDispatcher,
                                        @Nullable ChannelPermissionApprovalService channelApprovalService,
                                        @Nullable ChannelUserMappingCache userMappingCache,
                                        long maxAttachmentSize,
                                        int eventCacheMaxSize) {
        this.channelInstanceService = channelInstanceService;
        this.channelIngressService = channelIngressService;
        this.connectorRuntimeManager = connectorRuntimeManager;
        this.channelInstanceEventService = channelInstanceEventService;
        this.channelDeliveryDispatcher = channelDeliveryDispatcher;
        this.channelApprovalService = channelApprovalService;
        this.userMappingCache = userMappingCache;
        this.maxAttachmentSize = maxAttachmentSize > 0 ? maxAttachmentSize : DEFAULT_MAX_ATTACHMENT_SIZE;
        int cacheSize = eventCacheMaxSize > 0 ? eventCacheMaxSize : DEFAULT_EVENT_CACHE_MAX_SIZE;
        this.processedEventIds = Collections.synchronizedMap(
                new LinkedHashMap<>(cacheSize / 4, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > cacheSize;
                    }
                });
    }

    public ChannelRuntimeEventResponse processEvent(String instanceId, ChannelRuntimeEventRequest request) {
        String eventId = request.eventId();
        if (eventId != null && !eventId.isBlank()) {
            if (processedEventIds.putIfAbsent(eventId, Boolean.TRUE) != null) {
                log.info("重复事件已忽略: instanceId={}, eventId={}", instanceId, eventId);
                return ChannelRuntimeEventResponse.duplicate(eventId);
            }
        }
        ChannelInstance instance = requireActiveInstance(instanceId);

        // 自动学习平台用户 ID 和会话 ID 映射
        if (userMappingCache != null && request.userId() != null && !request.userId().isBlank()) {
            // request.sessionId() 是 connector 上报的平台会话 ID（如飞书 oc_xxx）
            userMappingCache.observe(instanceId, request.userId().trim(),
                    request.sessionId() != null && !request.sessionId().isBlank() ? request.sessionId().trim() : null);
        }

        // 拦截权限审批卡片回调 — 不走 Agent 管线，直接解析审批结果
        var approvalResponse = tryResolvePermissionApproval(instanceId, request);
        if (approvalResponse != null) {
            return approvalResponse;
        }

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
        String sessionId = firstNonBlank(request.sessionId(),
                resolveChannelSessionId(instance.instanceId(), request.userId().trim()));
        Instant timestamp = request.occurredAt() != null ? request.occurredAt() : Instant.now();

        return GatewayMessage.builder()
                .messageId(messageId)
                .channelType(resolveChannelType(instance.platform()))
                .userId(request.userId().trim())
                .sessionId(sessionId)
                .content(buildContent(request.content(), request.attachments()))
                .attachments(buildAttachments(request.attachments()))
                .channelMetadata(null)
                .timestamp(timestamp)
                .traceHeaders(buildTraceHeaders(instance, request))
                .build();
    }

    private MessageContent buildContent(ChannelRuntimeEventRequest.Content content,
                                         List<ChannelRuntimeEventRequest.Attachment> attachments) {
        String type = content.type() != null ? content.type().trim().toLowerCase() : "text";
        return switch (type) {
            case "command" -> buildCommandContent(content);
            case "event" -> buildEventContent(content);
            case "text" -> new MessageContent.TextMessage(requireText(content.text(), "text"));
            case "file", "image", "audio", "video" -> buildFileContent(content, type, attachments);
            case "card-action" -> buildCardActionContent(content);
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

    private MessageContent.FileMessage buildFileContent(ChannelRuntimeEventRequest.Content content,
                                                         String type,
                                                         List<ChannelRuntimeEventRequest.Attachment> attachments) {
        Map<String, Object> payload = content.payload();
        String fileName = payload != null && payload.get("fileName") instanceof String fn && !fn.isBlank()
                ? fn.trim()
                : defaultFileName(type);
        String mimeType = payload != null && payload.get("mimeType") instanceof String mt && !mt.isBlank()
                ? mt.trim()
                : defaultMimeType(type);
        String caption = content.text() != null && !content.text().isBlank() ? content.text().trim() : null;

        byte[] data;
        if (attachments != null && !attachments.isEmpty()) {
            ChannelRuntimeEventRequest.Attachment first = attachments.getFirst();
            try {
                data = Base64.getDecoder().decode(first.base64Data());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("富媒体附件 Base64 解码失败: " + fileName, e);
            }
        } else {
            // 没有附件数据，可能只有 fileToken 引用，Agent 后续通过工具下载
            data = new byte[0];
        }
        return new MessageContent.FileMessage(fileName, mimeType, data, caption);
    }

    private String defaultFileName(String type) {
        return switch (type) {
            case "image" -> "image.png";
            case "audio" -> "audio.mp3";
            case "video" -> "video.mp4";
            default -> "file.bin";
        };
    }

    private String defaultMimeType(String type) {
        return switch (type) {
            case "image" -> "image/png";
            case "audio" -> "audio/mpeg";
            case "video" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }

    private MessageContent.EventMessage buildCardActionContent(ChannelRuntimeEventRequest.Content content) {
        String eventType = content.name() != null && !content.name().isBlank()
                ? content.name().trim()
                : "card_action";
        Map<String, Object> payload = content.payload() != null ? content.payload() : Map.of();
        return new MessageContent.EventMessage(eventType, payload);
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
            if (data.length > maxAttachmentSize) {
                throw new IllegalArgumentException(
                        "附件大小超出限制（最大 %dMB）: fileName=%s, size=%d"
                                .formatted(maxAttachmentSize / 1024 / 1024, attachment.fileName(), data.length));
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
            case "qq" -> ChannelType.QQ;
            default -> throw new IllegalArgumentException("当前执行链路尚未支持该渠道平台: " + platform);
        };
    }

    private String requireText(String text, String contentType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(contentType + " 类型消息必须提供 text");
        }
        return text.trim();
    }

    /**
     * 解析渠道会话 ID — 空闲超时自动开新会话。
     *
     * <p>同一用户在同一渠道实例上，如果距上一条消息超过 30 分钟，
     * 自动生成新的 sessionId，避免所有对话堆积在同一个会话中。</p>
     */
    private String resolveChannelSessionId(String instanceId, String userId) {
        String compositeKey = instanceId + ":" + userId;
        long now = System.currentTimeMillis();

        var existing = channelSessions.get(compositeKey);
        if (existing != null && (now - existing.lastMessageAt()) < SESSION_IDLE_TIMEOUT_MS) {
            // 会话仍活跃，复用 sessionId，更新最后消息时间
            channelSessions.put(compositeKey, new ChannelSessionState(existing.sessionId(), now));
            return existing.sessionId();
        }

        // 超时或首次 — 创建新会话
        String newSessionId = instanceId + ":" + userId + ":" + UUID.randomUUID().toString().substring(0, 8);
        channelSessions.put(compositeKey, new ChannelSessionState(newSessionId, now));
        if (existing != null) {
            log.info("渠道会话空闲超时，自动开新会话: compositeKey={}, newSessionId={}", compositeKey, newSessionId);
        }
        return newSessionId;
    }

    /**
     *
     * <p>当 card_action 事件的 name 匹配 {@code permission_approval:{requestId}:approved/rejected} 时，
     * 直接解析审批结果并返回成功响应，不进入 Agent 管线。</p>
     *
     * @return 审批回调响应；非审批事件返回 null 继续正常流程
     */
    @Nullable
    private ChannelRuntimeEventResponse tryResolvePermissionApproval(String instanceId,
                                                                      ChannelRuntimeEventRequest request) {
        if (channelApprovalService == null || request.content() == null) {
            return null;
        }
        String contentType = request.content().type();
        if (!"card-action".equalsIgnoreCase(contentType)) {
            return null;
        }
        // 卡片回调的 action value 放在 name 或 payload.action 中
        String actionValue = request.content().name();
        if (actionValue == null || actionValue.isBlank()) {
            var payload = request.content().payload();
            if (payload != null && payload.get("action") instanceof String a) {
                actionValue = a;
            }
        }
        if (!ChannelPermissionApprovalService.isApprovalCallback(actionValue)) {
            return null;
        }
        String[] parsed = ChannelPermissionApprovalService.parseCallback(actionValue);
        if (parsed == null) {
            return null;
        }
        String requestId = parsed[0];
        boolean approved = "approved".equalsIgnoreCase(parsed[1]);
        boolean resolved = channelApprovalService.resolveApproval(requestId, approved);
        log.info("渠道审批卡片回调已处理: instanceId={}, requestId={}, approved={}, resolved={}",
                instanceId, requestId, approved, resolved);
        connectorRuntimeManager.markHeartbeat(instanceId);
        return ChannelRuntimeEventResponse.ok(request.eventId());
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
