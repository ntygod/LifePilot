package com.lifepilot.interaction.runtime;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.conversation.artifact.SessionArtifactRepository;
import com.lifepilot.interaction.config.GatewayDeliveryProperties;
import com.lifepilot.interaction.model.ArtifactRef;
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
import com.lifepilot.tool.artifact.ArtifactFilter;
import com.lifepilot.tool.model.ArtifactKind;
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
import java.util.Locale;
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
    /** 会话产物仓库 — 按 ArtifactRef 拉文件元信息和路径；为 null 时关闭文件下发。 */
    @Nullable
    private final SessionArtifactRepository artifactRepository;
    /** 渠道分发配置 — 提供平台大小上限、过滤规则等。 */
    @Nullable
    private final GatewayDeliveryProperties deliveryProperties;
    /** workspace 白名单根目录 — 二次校验，防 artifact 写入后被恶意覆盖路径。 */
    @Nullable
    private final Path workspaceRoot;

    /** 历史 5 参构造器 — 关闭 artifact 文件下发链路，保持向后兼容。 */
    public ChannelDeliveryDispatcher(ChannelRegistry channelRegistry,
                                     ChannelInstanceEventService channelInstanceEventService,
                                     RestClient restClient,
                                     ConnectorManager connectorManager,
                                     @Nullable SseSessionManager sseSessionManager) {
        this(channelRegistry, channelInstanceEventService, restClient,
                connectorManager, sseSessionManager, null, null, null);
    }

    /** 完整 8 参构造器 — 启用 artifact 文件下发链路。 */
    public ChannelDeliveryDispatcher(ChannelRegistry channelRegistry,
                                     ChannelInstanceEventService channelInstanceEventService,
                                     RestClient restClient,
                                     ConnectorManager connectorManager,
                                     @Nullable SseSessionManager sseSessionManager,
                                     @Nullable SessionArtifactRepository artifactRepository,
                                     @Nullable GatewayDeliveryProperties deliveryProperties,
                                     @Nullable Path workspaceRoot) {
        this.channelRegistry = channelRegistry;
        this.channelInstanceEventService = channelInstanceEventService;
        this.restClient = restClient;
        this.connectorManager = connectorManager;
        this.sseSessionManager = sseSessionManager;
        this.artifactRepository = artifactRepository;
        this.deliveryProperties = deliveryProperties;
        this.workspaceRoot = workspaceRoot;
    }

    public ChannelRuntimeEventResponse buildEventResponse(ChannelInstance instance,
                                                          ChannelRuntimeEventRequest request,
                                                          GatewayResponse response) {
        DeliveryMode mode = resolveDeliveryMode(request);
        ChannelRuntimeDeliveryRequest.Target target = buildTarget(request);
        String platform = instance.platform();
        long platformLimitBytes = resolvePlatformMaxSizeBytes(platform);

        // 1. 大小预检：超限的 artifact 不投递，主消息追加路径降级提示
        List<ArtifactRef> deliverableRefs = new ArrayList<>();
        StringBuilder oversizedNotices = new StringBuilder();
        for (ArtifactRef ref : response.artifactRefs()) {
            if (ref.size() > platformLimitBytes) {
                String absPath = resolveAbsolutePath(ref);
                long sizeMb = Math.max(1, ref.size() / 1024L / 1024L);
                long limitMb = Math.max(1, platformLimitBytes / 1024L / 1024L);
                oversizedNotices.append("\n\n📎 文件 ").append(ref.fileName())
                        .append(" (").append(sizeMb).append("MB) 超过 ")
                        .append(platform).append(" ").append(limitMb).append("MB 上限");
                if (absPath != null) {
                    oversizedNotices.append("，本地路径：").append(absPath);
                }
                log.warn("artifact 大小超限，跳过投递: platform={}, fileName={}, size={}, limit={}",
                        platform, ref.fileName(), ref.size(), platformLimitBytes);
            } else {
                deliverableRefs.add(ref);
            }
        }

        // 2. 主消息（含降级提示追加）
        ResponseContent mainContent = oversizedNotices.length() > 0
                ? appendNoticeToContent(response.content(), oversizedNotices.toString())
                : response.content();

        if (log.isDebugEnabled()) {
            log.debug("[dispatcher] buildEventResponse: instanceId={}, platform={}, contentType={}, artifactRefs={}, deliverable={}",
                    instance.instanceId(), platform,
                    mainContent != null ? mainContent.getClass().getSimpleName() : "null",
                    response.artifactRefs().size(), deliverableRefs.size());
        }

        ChannelRuntimeDeliveryRequest mainDelivery = new ChannelRuntimeDeliveryRequest(
                instance.instanceId(),
                response.responseId(),
                mode,
                target,
                buildContent(mainContent),
                buildAttachments(response.attachments()),
                response.metadata()
        );

        // 3. 每个可投递 artifact 拆成独立 delivery（responseId 加 :partN 后缀）
        List<ChannelRuntimeDeliveryRequest> deliveries = new ArrayList<>();
        deliveries.add(mainDelivery);
        int partIdx = 1;
        for (ArtifactRef ref : deliverableRefs) {
            String partResponseId = response.responseId() != null
                    ? response.responseId() + ":part" + partIdx
                    : null;
            ChannelRuntimeDeliveryRequest artifactDelivery = buildArtifactDelivery(
                    instance, partResponseId, ref, target, mode, response.metadata());
            if (artifactDelivery != null) {
                deliveries.add(artifactDelivery);
            }
            partIdx++;
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
     * 把单个 ArtifactRef 拼成一条 file/image content 的 delivery，含 base64 字节附件。
     *
     * <p>失败路径全部返回 null + WARN 日志，让主消息正常投递不阻塞响应链路。</p>
     */
    @Nullable
    private ChannelRuntimeDeliveryRequest buildArtifactDelivery(
            ChannelInstance instance,
            @Nullable String responseId,
            ArtifactRef ref,
            ChannelRuntimeDeliveryRequest.Target target,
            DeliveryMode mode,
            Map<String, Object> metadata) {
        if (artifactRepository == null) {
            return null;
        }
        var rowOpt = artifactRepository.findById(ref.artifactId());
        if (rowOpt.isEmpty()) {
            log.warn("artifact 不存在: id={}", ref.artifactId());
            return null;
        }
        Map<String, Object> payload = artifactRepository.readPayload(ref.artifactId());
        Object pathObj = payload.get("path");
        if (!(pathObj instanceof String pathStr) || pathStr.isBlank()) {
            log.warn("artifact 缺少 path 字段: id={}", ref.artifactId());
            return null;
        }
        Path path = Paths.get(pathStr);

        // 二次校验 workspace 白名单
        if (workspaceRoot != null && !ArtifactFilter.isInWorkspaceRoot(path, workspaceRoot)) {
            log.warn("artifact 路径越界，跳过投递: id={}, path={}", ref.artifactId(), path);
            return null;
        }
        if (!Files.isRegularFile(path)) {
            log.warn("artifact 物理文件丢失: id={}, path={}", ref.artifactId(), path);
            return null;
        }

        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("artifact 字节读取失败: id={}, path={}, error={}",
                    ref.artifactId(), path, e.getMessage());
            return null;
        }

        var attachment = new ChannelRuntimeDeliveryRequest.Attachment(
                ref.artifactId(),
                ref.fileName(),
                ref.mimeType(),
                Base64.getEncoder().encodeToString(data),
                data.length
        );

        String contentType = ref.kind() == ArtifactKind.IMAGE ? "image" : "file";
        Map<String, Object> contentPayload = new LinkedHashMap<>();
        contentPayload.put("artifactId", ref.artifactId());
        contentPayload.put("fileName", ref.fileName());
        contentPayload.put("mimeType", ref.mimeType());
        contentPayload.put("kind", ref.kind().name());
        contentPayload.put("size", ref.size());

        return new ChannelRuntimeDeliveryRequest(
                instance.instanceId(),
                responseId,
                mode,
                target,
                new ChannelRuntimeDeliveryRequest.Content(contentType, "[" + ref.fileName() + "]",
                        Map.copyOf(contentPayload)),
                List.of(attachment),
                metadata
        );
    }

    /** 查询指定平台的单文件大小上限（字节）；未配置时回落 50MB。 */
    private long resolvePlatformMaxSizeBytes(String platform) {
        int mb = (deliveryProperties != null)
                ? deliveryProperties.resolvePlatformMaxSizeMb(platform)
                : GatewayDeliveryProperties.FALLBACK_PLATFORM_MAX_SIZE_MB;
        return mb * 1024L * 1024L;
    }

    /** 通过 SessionArtifactRepository 反查 artifact 物理路径，用于降级提示文本。 */
    @Nullable
    private String resolveAbsolutePath(ArtifactRef ref) {
        if (artifactRepository == null) {
            return null;
        }
        try {
            Map<String, Object> payload = artifactRepository.readPayload(ref.artifactId());
            Object p = payload.get("path");
            return p instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在主消息内容末尾追加降级提示文本；TextContent / MarkdownContent 各自处理，
     * 其他 sealed 分支保持不变（notice 只对文本类型生效）。
     */
    private ResponseContent appendNoticeToContent(ResponseContent content, String notice) {
        if (content == null) {
            return new ResponseContent.MarkdownContent(notice.strip());
        }
        return switch (content) {
            case ResponseContent.TextContent t ->
                    new ResponseContent.TextContent((t.text() != null ? t.text() : "") + notice);
            case ResponseContent.MarkdownContent m ->
                    new ResponseContent.MarkdownContent((m.markdown() != null ? m.markdown() : "") + notice);
            default -> content;
        };
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
