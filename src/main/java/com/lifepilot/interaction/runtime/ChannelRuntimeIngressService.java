package com.lifepilot.interaction.runtime;

import com.lifepilot.interaction.attachment.DocumentAttachmentHintBuilder;
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
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    /**
     * 附件持久化仓储，可选。非 null 时 {@link #buildAttachments} 会把 base64 数据落盘到
     * {@link #attachmentStorageDir} 并 {@code saveForEntry(entryId=null, ...)}（orphan 模式，
     * 后续由 {@code AgentPersistenceHandler.backfillOrphanEntryIds} 关联 entry）；
     * 为 null 时走 in-memory 兼容模式（仅测试场景）。
     */
    @Nullable
    private final AttachmentRepository attachmentRepository;
    /** 附件落盘目录，与 {@link #attachmentRepository} 成对存在。 */
    @Nullable
    private final String attachmentStorageDir;
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
                null, null,
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
        this(channelInstanceService, channelIngressService, connectorRuntimeManager,
                channelInstanceEventService, channelDeliveryDispatcher,
                channelApprovalService, userMappingCache,
                null, null,
                maxAttachmentSize, eventCacheMaxSize);
    }

    public ChannelRuntimeIngressService(ChannelInstanceService channelInstanceService,
                                        ChannelIngressService channelIngressService,
                                        ConnectorRuntimeManager connectorRuntimeManager,
                                        ChannelInstanceEventService channelInstanceEventService,
                                        ChannelDeliveryDispatcher channelDeliveryDispatcher,
                                        @Nullable ChannelPermissionApprovalService channelApprovalService,
                                        @Nullable ChannelUserMappingCache userMappingCache,
                                        @Nullable AttachmentRepository attachmentRepository,
                                        @Nullable String attachmentStorageDir,
                                        long maxAttachmentSize,
                                        int eventCacheMaxSize) {
        this.channelInstanceService = channelInstanceService;
        this.channelIngressService = channelIngressService;
        this.connectorRuntimeManager = connectorRuntimeManager;
        this.channelInstanceEventService = channelInstanceEventService;
        this.channelDeliveryDispatcher = channelDeliveryDispatcher;
        this.channelApprovalService = channelApprovalService;
        this.userMappingCache = userMappingCache;
        this.attachmentRepository = attachmentRepository;
        this.attachmentStorageDir = attachmentStorageDir;
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

        // 附件先落盘 + save repo（若 AttachmentRepository 已装配），拿到 attachmentId；
        // 再让 text 类型 content 注入文档附件 hint，引导 LLM 用 file.read / document.edit
        List<GatewayMessage.Attachment> attachments = buildAttachments(sessionId, request.attachments());
        MessageContent content = buildContent(request.content(), request.attachments(), attachments);

        return GatewayMessage.builder()
                .messageId(messageId)
                .channelType(resolveChannelType(instance.platform()))
                .userId(request.userId().trim())
                .sessionId(sessionId)
                .content(content)
                .attachments(attachments)
                .channelMetadata(null)
                .timestamp(timestamp)
                .traceHeaders(buildTraceHeaders(instance, request))
                .build();
    }

    private MessageContent buildContent(ChannelRuntimeEventRequest.Content content,
                                         List<ChannelRuntimeEventRequest.Attachment> attachments,
                                         List<GatewayMessage.Attachment> persistedAttachments) {
        String type = content.type() != null ? content.type().trim().toLowerCase() : "text";
        log.debug("channel buildContent 分支: type={}, rawText长度={}, persistedAttachments={}",
                type,
                content.text() == null ? -1 : content.text().length(),
                persistedAttachments.size());
        return switch (type) {
            case "command" -> buildCommandContent(content);
            case "event" -> buildEventContent(content);
            case "text" -> textContentWithHint(requireText(content.text(), "text"), persistedAttachments);
            // 原本这些类型走 MessageContent.FileMessage，但整个代码库下游没有任何消费者处理 FileMessage
            // （Agent / Router / Middleware 只看 TextMessage / CommandMessage），导致 LLM 收不到附件信息。
            // 统一走 TextMessage：caption 作为 text，文档 hint 注入其中；binary 已独立在
            // GatewayMessage.attachments 旁挂，LLM 通过 attachmentId 用 file.read 访问。
            case "file", "image", "audio", "video" -> buildFileAsTextContent(content, type, persistedAttachments);
            case "card-action" -> buildCardActionContent(content);
            default -> throw new IllegalArgumentException("不支持的 connector 内容类型: " + type);
        };
    }

    /** text 分支封装：插入 hint 并把是否注入成功打到 DEBUG 日志便于排查。 */
    private MessageContent.TextMessage textContentWithHint(String raw,
                                                            List<GatewayMessage.Attachment> persistedAttachments) {
        String withHint = DocumentAttachmentHintBuilder.appendHint(raw, persistedAttachments);
        log.debug("channel text 分支 hint 注入: 原文长度={}, 注入后长度={}, 是否含 sentinel={}",
                raw.length(), withHint.length(),
                withHint.contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN));
        return new MessageContent.TextMessage(withHint);
    }

    /** file/image/audio/video：统一生成 TextMessage(caption + hint)，binary 已独立旁挂。 */
    private MessageContent.TextMessage buildFileAsTextContent(ChannelRuntimeEventRequest.Content content,
                                                               String type,
                                                               List<GatewayMessage.Attachment> persistedAttachments) {
        String caption = content.text() != null && !content.text().isBlank() ? content.text().trim() : "";
        String text = DocumentAttachmentHintBuilder.appendHint(caption, persistedAttachments);
        log.debug("channel file 分支 hint 注入: type={}, caption长度={}, 注入后长度={}, 是否含 sentinel={}",
                type, caption.length(), text.length(),
                text.contains(DocumentAttachmentHintBuilder.DOCUMENT_HINT_BEGIN));
        if (text.isEmpty()) {
            // 没 caption 也没文档附件（比如纯图片），给一行占位避免 TextMessage 空串
            text = "[" + type + "]";
        }
        return new MessageContent.TextMessage(text);
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

    private MessageContent.EventMessage buildCardActionContent(ChannelRuntimeEventRequest.Content content) {
        String eventType = content.name() != null && !content.name().isBlank()
                ? content.name().trim()
                : "card_action";
        Map<String, Object> payload = content.payload() != null ? content.payload() : Map.of();
        return new MessageContent.EventMessage(eventType, payload);
    }

    private List<GatewayMessage.Attachment> buildAttachments(String sessionId,
                                                              List<ChannelRuntimeEventRequest.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        boolean persistEnabled = attachmentRepository != null && attachmentStorageDir != null;
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
            String fileName = attachment.fileName() != null && !attachment.fileName().isBlank()
                    ? attachment.fileName().trim()
                    : UUID.randomUUID().toString();
            String reportedMime = attachment.mimeType() != null && !attachment.mimeType().isBlank()
                    ? attachment.mimeType().trim()
                    : "application/octet-stream";
            // connector 上报的 MIME 经常不准（飞书 xlsx 也报 application/octet-stream），按扩展名兜底校正。
            // 校正后的 mimeType 是数据层的"真相"：存进 DB + GatewayMessage，下游 parser 路由 / 白名单匹配都靠它。
            String mimeType = inferMimeFromFileName(fileName, reportedMime);
            long size = attachment.size() > 0 ? attachment.size() : data.length;

            String attachmentId;
            if (persistEnabled) {
                // 落盘 + saveForEntry(null) orphan 模式：后续 AgentPersistenceHandler.backfillOrphanEntryIds
                // 把本会话 orphan 附件统一挂到 user / assistant entry。
                attachmentId = persistAttachment(sessionId, fileName, mimeType, data, size);
            } else {
                attachmentId = attachment.attachmentId() != null && !attachment.attachmentId().isBlank()
                        ? attachment.attachmentId().trim()
                        : UUID.randomUUID().toString();
            }
            log.debug("channel 附件出口: persistEnabled={}, attachmentId={}, fileName={}, mimeType={}",
                    persistEnabled, attachmentId, fileName, mimeType);
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

    /**
     * 把 channel 附件落盘到 {@code <attachmentStorageDir>/<uuid>_<fileName>} 并 save 到
     * {@code message_attachments} 表（entryId=null orphan 模式）。
     * 失败 fallback：log.warn 后返回随机 UUID，保证 ingress 主流程不断裂。
     */
    private String persistAttachment(String sessionId, String fileName, String mimeType, byte[] data, long size) {
        assert attachmentRepository != null && attachmentStorageDir != null;
        try {
            Path storageRoot = Paths.get(attachmentStorageDir);
            Files.createDirectories(storageRoot);
            String storedFileName = UUID.randomUUID() + "_" + fileName;
            Path filePath = storageRoot.resolve(storedFileName);
            Files.write(filePath, data);
            return attachmentRepository.saveForEntry(
                    null,             // entryId：orphan，后续 backfill 挂到 user/assistant entry
                    sessionId,
                    fileName,
                    filePath.toString(),
                    size,
                    mimeType,
                    null              // url：主服务内部引用通过 attachmentId 查，不依赖 url 字段
            );
        } catch (IOException e) {
            log.warn("channel 附件落盘失败，降级为 in-memory 附件：sessionId={}, fileName={}",
                    sessionId, fileName, e);
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 按文件名扩展名推断 MIME，纠正 connector 上报不准的情况。
     *
     * <p>规则：仅当 reportedMime 为 {@code application/octet-stream} 或空时触发兜底；
     * reportedMime 明确且非 octet-stream 的信任原值（connector 可能有针对性识别）。
     * 未知扩展名保持 octet-stream（下游会走通用二进制路径）。</p>
     */
    static String inferMimeFromFileName(String fileName, String reportedMime) {
        boolean generic = reportedMime == null || reportedMime.isBlank()
                || "application/octet-stream".equalsIgnoreCase(reportedMime);
        if (!generic) return reportedMime;
        if (fileName == null) return reportedMime;
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return reportedMime;
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf" -> "application/pdf";
            case "md", "markdown", "mkd" -> "text/markdown";
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "txt", "text", "log" -> "text/plain";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "html", "htm" -> "text/html";
            default -> reportedMime;
        };
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
