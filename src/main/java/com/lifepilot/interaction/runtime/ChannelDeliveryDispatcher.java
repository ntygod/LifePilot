package com.lifepilot.interaction.runtime;

import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeEventResponse;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渠道出站分发器。
 *
 * <p>统一负责两类出站：</p>
 * <ul>
 *   <li>对 connector 入站事件的同步回复组装</li>
 *   <li>主动消息的本地分发或外部 connector 投递</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelDeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelDeliveryDispatcher.class);

    /** 匹配 AI reply 里主服务生成的文档下载链接。格式稳定：{@code /api/documents/<uuid>/download}。 */
    private static final Pattern DOCUMENT_DOWNLOAD_URL =
            Pattern.compile("/api/documents/([0-9a-f\\-]{36})/download");
    /** 匹配 markdown 链接 {@code [text](url)}：用于把整段"[下载 xxx](/api/documents/../download)" 整体替换为空。 */
    private static final Pattern MARKDOWN_LINK_TO_DOWNLOAD = Pattern.compile(
            "!?\\[[^\\]]*\\]\\(/api/documents/([0-9a-f\\-]{36})/download\\)");

    private final ChannelRegistry channelRegistry;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final RestClient restClient;
    private final ConnectorManager connectorManager;
    @Nullable
    private final SseSessionManager sseSessionManager;
    @Nullable
    private final SessionDocumentRepository documentRepository;

    public ChannelDeliveryDispatcher(ChannelRegistry channelRegistry,
                                     ChannelInstanceEventService channelInstanceEventService,
                                     RestClient restClient,
                                     ConnectorManager connectorManager,
                                     @Nullable SseSessionManager sseSessionManager) {
        this(channelRegistry, channelInstanceEventService, restClient, connectorManager,
                sseSessionManager, null);
    }

    public ChannelDeliveryDispatcher(ChannelRegistry channelRegistry,
                                     ChannelInstanceEventService channelInstanceEventService,
                                     RestClient restClient,
                                     ConnectorManager connectorManager,
                                     @Nullable SseSessionManager sseSessionManager,
                                     @Nullable SessionDocumentRepository documentRepository) {
        this.channelRegistry = channelRegistry;
        this.channelInstanceEventService = channelInstanceEventService;
        this.restClient = restClient;
        this.connectorManager = connectorManager;
        this.sseSessionManager = sseSessionManager;
        this.documentRepository = documentRepository;
    }

    public ChannelRuntimeEventResponse buildEventResponse(ChannelInstance instance,
                                                          ChannelRuntimeEventRequest request,
                                                          GatewayResponse response) {
        // 把 AI reply 里主服务生成的 /api/documents/<id>/download 链接拆成独立的 FileContent，
        // 让 connector 发成真实文件消息（web 场景也这么拆没有副作用：web 层走 SSE 不经过这个 dispatcher）
        // 传入当前 channel session 做归属校验：防 prompt injection 让 AI 引用他人 session 的 documentId 越权
        List<ResponseContent> contents = splitFileReferences(response.content(), request.sessionId());
        if (log.isDebugEnabled()) {
            log.debug("[dispatcher] buildEventResponse: instanceId={}, platform={}, originalType={}, splitCount={}, types={}",
                    instance.instanceId(), instance.platform(),
                    response.content() != null ? response.content().getClass().getSimpleName() : "null",
                    contents.size(),
                    contents.stream().map(c -> c.getClass().getSimpleName()).toList());
        }
        DeliveryMode mode = resolveDeliveryMode(request);
        ChannelRuntimeDeliveryRequest.Target target = buildTarget(request);

        List<ChannelRuntimeDeliveryRequest> deliveries = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i++) {
            ResponseContent c = contents.get(i);
            // 第一条 delivery 保留原附件；后续 delivery（通常是单独的 FileContent）自带 attachments
            List<ChannelRuntimeDeliveryRequest.Attachment> deliveryAttachments = i == 0
                    ? buildAttachments(response.attachments())
                    : buildAttachmentsForFileContent(c);
            // 拆分后第 2+ 条必须用独立 responseId。connector 侧会按 responseId 去查 previousMessage
            // 做"流式更新同一条消息"的 PATCH，共用 responseId 会导致后续 FileContent 被当成
            // 前一条 markdown 消息的更新、被强制重渲染成 interactive 卡片、根本不走文件上传。
            String deliveryResponseId = i == 0 || response.responseId() == null
                    ? response.responseId()
                    : response.responseId() + ":part" + i;
            deliveries.add(new ChannelRuntimeDeliveryRequest(
                    instance.instanceId(),
                    deliveryResponseId,
                    mode,
                    target,
                    buildContent(c),
                    deliveryAttachments,
                    response.metadata()
            ));
        }

        return new ChannelRuntimeEventResponse(
                true,
                response.responseId(),
                response.statusCode(),
                response.errorMessage(),
                List.copyOf(deliveries)
        );
    }

    /**
     * 识别 reply 文本里 {@code /api/documents/<id>/download} 引用的 document，拆成
     * 文本 + FileContent 的序列，保留文本里的说明（表格摘要等）。无匹配时原样单元素列表返回。
     * 依赖 documentRepository 查文件元信息；未注入时不拆分。
     */
    private List<ResponseContent> splitFileReferences(ResponseContent content, @Nullable String sessionId) {
        if (documentRepository == null) {
            return List.of(content);
        }
        String text;
        boolean isMarkdown;
        if (content instanceof ResponseContent.TextContent t) {
            text = t.text();
            isMarkdown = false;
        } else if (content instanceof ResponseContent.MarkdownContent m) {
            text = m.markdown();
            isMarkdown = true;
        } else {
            return List.of(content);
        }
        if (text == null || text.isEmpty()) {
            return List.of(content);
        }
        Matcher matcher = DOCUMENT_DOWNLOAD_URL.matcher(text);
        if (!matcher.find()) {
            return List.of(content);
        }

        // 收集所有唯一 documentId（保持顺序）
        List<String> documentIds = new ArrayList<>();
        matcher.reset();
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!documentIds.contains(id)) documentIds.add(id);
        }

        // 把 markdown 下载链接从文本里剥离，保留其它说明文字
        String stripped = MARKDOWN_LINK_TO_DOWNLOAD.matcher(text).replaceAll("")
                // 兜底移除裸 url
                .replaceAll("/api/documents/[0-9a-f\\-]{36}/download", "")
                // 清理因剥离产生的多余空行
                .replaceAll("(?m)^[\\s\\u00A0]+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();

        List<ResponseContent> result = new ArrayList<>();
        if (!stripped.isEmpty()) {
            result.add(isMarkdown
                    ? new ResponseContent.MarkdownContent(stripped)
                    : new ResponseContent.TextContent(stripped));
        }
        for (String docId : documentIds) {
            var record = documentRepository.findById(docId);
            if (record == null) {
                log.warn("reply 里引用的 document 不存在，跳过文件消息：documentId={}", docId);
                continue;
            }
            // 归属校验：防 AI 通过 prompt injection 引用他人 session 的 documentId 越权读文件
            if (sessionId != null && record.sessionId() != null
                    && !sessionId.equals(record.sessionId())) {
                log.warn("reply 引用的 document 不属于当前会话，拒绝跨会话投递：documentId={}, docSession={}, currentSession={}",
                        docId, record.sessionId(), sessionId);
                continue;
            }
            result.add(new ResponseContent.FileContent(
                    record.id(), record.fileName(), record.mimeType(), null));
        }
        return result.isEmpty() ? List.of(content) : List.copyOf(result);
    }

    /** 对 FileContent 从物理文件读 byte 构造 Attachment（base64）供 connector 发送。 */
    private List<ChannelRuntimeDeliveryRequest.Attachment> buildAttachmentsForFileContent(ResponseContent c) {
        if (!(c instanceof ResponseContent.FileContent f) || documentRepository == null) {
            return List.of();
        }
        var record = documentRepository.findById(f.documentId());
        if (record == null) return List.of();
        Path filePath = Paths.get(record.filePath());
        if (!Files.isRegularFile(filePath)) {
            log.warn("FileContent 指向的物理文件不存在，跳过：documentId={}, path={}",
                    f.documentId(), filePath);
            return List.of();
        }
        try {
            byte[] data = Files.readAllBytes(filePath);
            return List.of(new ChannelRuntimeDeliveryRequest.Attachment(
                    record.id(),
                    record.fileName(),
                    record.mimeType(),
                    Base64.getEncoder().encodeToString(data),
                    data.length
            ));
        } catch (IOException e) {
            log.warn("读 FileContent 物理文件失败：documentId={}, path={}", f.documentId(), filePath, e);
            return List.of();
        }
    }

    public void deliver(ChannelInstance instance, ChannelRuntimeDeliveryRequest request) {
        var plugin = channelRegistry.find(instance.pluginId())
                .orElseThrow(() -> new IllegalArgumentException("未注册的渠道插件: " + instance.pluginId()));
        if (plugin.connectorMode() == ConnectorMode.LOCAL) {
            deliverLocal(instance, request);
            return;
        }
        deliverExternal(instance, request);
    }

    public void broadcastNotification(Object payload) {
        if (sseSessionManager == null) {
            throw new IllegalStateException("SseSessionManager 不可用，无法广播本地通知");
        }
        sseSessionManager.broadcastNotification(payload);
    }

    private DeliveryMode resolveDeliveryMode(ChannelRuntimeEventRequest request) {
        if (request.deliveryHints() != null && request.deliveryHints().deliveryMode() != null) {
            return request.deliveryHints().deliveryMode();
        }
        return DeliveryMode.ASYNC_PUSH;
    }

    private ChannelRuntimeDeliveryRequest.Target buildTarget(ChannelRuntimeEventRequest request) {
        ChannelRuntimeEventRequest.ReplyTarget replyTarget = request.replyTarget();
        String userId = replyTarget != null && replyTarget.userId() != null && !replyTarget.userId().isBlank()
                ? replyTarget.userId().trim()
                : request.userId();
        String sessionId = replyTarget != null && replyTarget.sessionId() != null && !replyTarget.sessionId().isBlank()
                ? replyTarget.sessionId().trim()
                : request.sessionId();
        Map<String, Object> attributes = replyTarget != null && replyTarget.attributes() != null
                ? replyTarget.attributes()
                : Map.of();
        return new ChannelRuntimeDeliveryRequest.Target(userId, sessionId, attributes);
    }

    public ChannelRuntimeDeliveryRequest.Content buildContent(ResponseContent content) {
        return switch (content) {
            case ResponseContent.TextContent text -> new ChannelRuntimeDeliveryRequest.Content(
                    "text",
                    text.text(),
                    Map.of("text", text.text())
            );
            case ResponseContent.MarkdownContent markdown -> new ChannelRuntimeDeliveryRequest.Content(
                    "markdown",
                    markdown.markdown(),
                    Map.of("markdown", markdown.markdown())
            );
            case ResponseContent.CardContent card -> new ChannelRuntimeDeliveryRequest.Content(
                    "card",
                    card.toPlainText(),
                    buildCardPayload(card)
            );
            case ResponseContent.ImageContent image -> new ChannelRuntimeDeliveryRequest.Content(
                    "image",
                    image.toPlainText(),
                    buildImagePayload(image)
            );
            case ResponseContent.FileContent file -> new ChannelRuntimeDeliveryRequest.Content(
                    "file",
                    file.toPlainText(),
                    buildFilePayload(file)
            );
            case ResponseContent.StreamingContent streaming -> new ChannelRuntimeDeliveryRequest.Content(
                    "streaming",
                    streaming.toPlainText(),
                    Map.of("streamId", streaming.streamId())
            );
        };
    }

    public List<ChannelRuntimeDeliveryRequest.Attachment> buildAttachments(List<GatewayMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ChannelRuntimeDeliveryRequest.Attachment> results = new ArrayList<>(attachments.size());
        for (GatewayMessage.Attachment attachment : attachments) {
            results.add(new ChannelRuntimeDeliveryRequest.Attachment(
                    attachment.attachmentId(),
                    attachment.fileName(),
                    attachment.mimeType(),
                    Base64.getEncoder().encodeToString(attachment.data()),
                    attachment.size()
            ));
        }
        return List.copyOf(results);
    }

    private Map<String, Object> buildCardPayload(ResponseContent.CardContent card) {
        List<Map<String, String>> actions = card.actions().stream()
                .map(action -> Map.of(
                        "label", action.label(),
                        "type", action.type(),
                        "value", action.value()
                ))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", card.title());
        payload.put("body", card.body());
        payload.put("actions", actions);
        return Map.copyOf(payload);
    }

    private Map<String, Object> buildFilePayload(ResponseContent.FileContent file) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", file.documentId());
        payload.put("fileName", file.fileName());
        payload.put("mimeType", file.mimeType());
        if (file.caption() != null && !file.caption().isBlank()) {
            payload.put("caption", file.caption());
        }
        return Map.copyOf(payload);
    }

    private Map<String, Object> buildImagePayload(ResponseContent.ImageContent image) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("imageUrl", image.imageUrl());
        payload.put("altText", image.altText());
        if (image.caption() != null && !image.caption().isBlank()) {
            payload.put("caption", image.caption());
        }
        return Map.copyOf(payload);
    }

    private void deliverLocal(ChannelInstance instance, ChannelRuntimeDeliveryRequest request) {
        if (!"web".equalsIgnoreCase(instance.platform())) {
            throw new IllegalArgumentException("当前仅支持 Web 本地渠道主动发送: " + instance.platform());
        }
        throw new IllegalStateException("Web 本地主动发送应由上层传入原始通知 payload");
    }

    private void deliverExternal(ChannelInstance instance, ChannelRuntimeDeliveryRequest request) {
        var plugin = channelRegistry.find(instance.pluginId())
                .orElseThrow(() -> new IllegalArgumentException("未注册的渠道插件: " + instance.pluginId()));
        String baseUrl = resolveConnectorBaseUrl(instance, plugin);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("渠道实例未配置 connector baseUrl: " + instance.instanceId());
        }
        String instanceToken = resolveInstanceToken(instance);
        if (instanceToken == null || instanceToken.isBlank()) {
            throw new IllegalStateException("渠道实例未配置 runtimeToken: " + instance.instanceId());
        }

        String deliverUrl = normalizeBaseUrl(baseUrl) + "/instances/" + instance.instanceId() + "/deliver";
        restClient.post()
                .uri(deliverUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header(ChannelRuntimeProtocol.HEADER_INSTANCE_TOKEN, instanceToken)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        channelInstanceEventService.record(
                instance.instanceId(),
                "DELIVERY_DISPATCHED",
                "主动消息已投递到 connector",
                buildDeliveryPayload(request)
        );
        log.debug("主动消息已投递到外部 connector: instanceId={}, responseId={}",
                instance.instanceId(), request.responseId());
    }

    @Nullable
    private String resolveConnectorBaseUrl(ChannelInstance instance,
                                           com.lifepilot.interaction.model.ChannelPluginDescriptor plugin) {
        String managedBaseUrl = connectorManager.resolveBaseUrl(instance, plugin, true);
        if (managedBaseUrl != null && !managedBaseUrl.isBlank()) {
            return managedBaseUrl;
        }
        if (plugin.connectorSpec() == null) {
            return null;
        }
        Object fromSpec = plugin.connectorSpec().get("baseUrl");
        if (fromSpec instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        Object fromSpecUrl = plugin.connectorSpec().get("url");
        return fromSpecUrl instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    @Nullable
    private String resolveInstanceToken(ChannelInstance instance) {
        if (instance.secretConfig() == null) {
            return null;
        }
        Object raw = instance.secretConfig().get(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY);
        return raw instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private Map<String, Object> buildDeliveryPayload(ChannelRuntimeDeliveryRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.responseId() != null && !request.responseId().isBlank()) {
            payload.put("responseId", request.responseId());
        }
        payload.put("deliveryMode", request.deliveryMode().name());
        payload.put("contentType", request.content().type() != null ? request.content().type() : "text");
        if (request.target() != null) {
            if (request.target().userId() != null && !request.target().userId().isBlank()) {
                payload.put("targetUserId", request.target().userId());
            }
            if (request.target().sessionId() != null && !request.target().sessionId().isBlank()) {
                payload.put("targetSessionId", request.target().sessionId());
            }
        }
        payload.put("attachmentCount", request.attachments() != null ? request.attachments().size() : 0);
        return Map.copyOf(payload);
    }
}
