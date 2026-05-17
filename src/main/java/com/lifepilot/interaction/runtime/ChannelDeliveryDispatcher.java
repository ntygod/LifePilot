package com.lifepilot.interaction.runtime;

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

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final ChannelRegistry channelRegistry;
    private final ChannelInstanceEventService channelInstanceEventService;
    private final RestClient restClient;
    private final ConnectorManager connectorManager;
    @Nullable
    private final SseSessionManager sseSessionManager;

    public ChannelDeliveryDispatcher(ChannelRegistry channelRegistry,
                                     ChannelInstanceEventService channelInstanceEventService,
                                     RestClient restClient,
                                     ConnectorManager connectorManager,
                                     @Nullable SseSessionManager sseSessionManager) {
        this.channelRegistry = channelRegistry;
        this.channelInstanceEventService = channelInstanceEventService;
        this.restClient = restClient;
        this.connectorManager = connectorManager;
        this.sseSessionManager = sseSessionManager;
    }

    public ChannelRuntimeEventResponse buildEventResponse(ChannelInstance instance,
                                                          ChannelRuntimeEventRequest request,
                                                          GatewayResponse response) {
        // 文档工作区下架后不再拆分 /api/documents/<id>/download 链接：直接以单条 delivery 投递，
        // 链接保留在原始文本中（下游渠道按普通文本渲染）。
        if (log.isDebugEnabled()) {
            log.debug("[dispatcher] buildEventResponse: instanceId={}, platform={}, contentType={}",
                    instance.instanceId(), instance.platform(),
                    response.content() != null ? response.content().getClass().getSimpleName() : "null");
        }
        DeliveryMode mode = resolveDeliveryMode(request);
        ChannelRuntimeDeliveryRequest.Target target = buildTarget(request);

        ChannelRuntimeDeliveryRequest delivery = new ChannelRuntimeDeliveryRequest(
                instance.instanceId(),
                response.responseId(),
                mode,
                target,
                buildContent(response.content()),
                buildAttachments(response.attachments()),
                response.metadata()
        );

        return new ChannelRuntimeEventResponse(
                true,
                response.responseId(),
                response.statusCode(),
                response.errorMessage(),
                List.of(delivery)
        );
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
