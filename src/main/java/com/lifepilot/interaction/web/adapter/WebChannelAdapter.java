package com.lifepilot.interaction.web.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lifepilot.notification.Urgency;
import com.lifepilot.interaction.channel.AbstractChannelAdapter;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.NotificationSseEvent;
import com.lifepilot.interaction.web.model.SignalRequest;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.audio.AudioTranscriptionException;
import com.lifepilot.media.config.MediaProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * Web 通道适配器，桥接 REST 请求与 MessageGateway 中间件管道。
 *
 * <p>将 {@link ChatRequest} 和 {@link SignalRequest} 标准化为 {@link GatewayMessage}，
 * 通过 {@link MessageGateway#process} 推入中间件管道处理。Controller 负责 HTTP 协议层，
 * 本适配器负责 GatewayMessage 转换和 Gateway 调用，职责分离。</p>
 *
 * <p>Phase 2：图片多模态支持
 * 在此处根据 ChatRequest.attachmentIds 加载消息附件二进制数据，并封装到
 * {@link GatewayMessage#attachments()} 中，为后续多模态路由提供输入。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class WebChannelAdapter extends AbstractChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebChannelAdapter.class);

    /** A2UI 信号事件类型常量。 */
    private static final String A2UI_SIGNAL_EVENT_TYPE = "a2ui_signal";

    /** Web 通道默认用户标识。 */
    private static final String DEFAULT_WEB_USER = "web-user";

    private final AttachmentRepository attachmentRepository;
    @Nullable private final SseSessionManager sseSessionManager;
    @Nullable private final AudioTranscriber audioTranscriber;
    private final MediaProperties mediaProperties;

    public WebChannelAdapter(MessageGateway gateway,
                             GatewayProperties properties,
                             AttachmentRepository attachmentRepository,
                             @Nullable SseSessionManager sseSessionManager,
                             @Nullable AudioTranscriber audioTranscriber,
                             MediaProperties mediaProperties) {
        super(gateway, properties);
        this.attachmentRepository = attachmentRepository;
        this.sseSessionManager = sseSessionManager;
        this.audioTranscriber = audioTranscriber;
        this.mediaProperties = mediaProperties;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.WEB;
    }

    @Override
    public GatewayMessage normalize(Object rawMessage) {
        return switch (rawMessage) {
            case ChatRequest req -> buildChatGatewayMessage(req, null, false);
            case SignalRequest req -> buildSignalGatewayMessage(req, null);
            default -> throw new IllegalArgumentException(
                    "不支持的消息类型: " + rawMessage.getClass().getName());
        };
    }

    @Override
    protected void doStart() {
        // Web 通道无需特殊启动逻辑（HTTP 端点由 Spring MVC 管理）
        log.info("Web 通道适配器已启动");
    }

    @Override
    protected void doStop() {
        // 实际 SseEmitter 清理由 SseSessionManager 负责
        log.info("Web 通道适配器停止中，SseEmitter 清理委托给 SseSessionManager");
    }

    @Override
    protected void doSendResponse(String userId, GatewayResponse response) {
        // 检查 metadata 中是否包含 notificationType（主动推理通知）
        var metadata = response.metadata();
        if (metadata != null && metadata.containsKey("notificationType") && sseSessionManager != null) {
            try {
                var typeId = String.valueOf(metadata.get("notificationType"));
                var urgencyStr = metadata.containsKey("urgency")
                        ? String.valueOf(metadata.get("urgency")) : "LOW";
                var urgency = Urgency.valueOf(urgencyStr);
                var notificationId = metadata.containsKey("notificationId")
                        ? String.valueOf(metadata.get("notificationId")) : UUID.randomUUID().toString();
                var content = response.content() != null ? response.content().toPlainText() : "";

                var event = new NotificationSseEvent(
                        notificationId, typeId, urgency, content,
                        java.time.Instant.now().toString());
                sseSessionManager.broadcastNotification(event);
                log.debug("通知 SSE 广播完成: typeId={}, urgency={}", typeId, urgency);
                return;
            } catch (Exception e) {
                log.warn("通知 SSE 广播失败，降级为默认处理: userId={}, error={}", userId, e.getMessage());
            }
        }

        // Web 通道的响应通过 Controller 直接返回，此方法用于异步场景
        log.debug("Web 通道异步响应: userId={}, statusCode={}", userId, response.statusCode());
    }

    /**
     * 同步处理消息（非流式，Controller 直接调用）。
     *
     * @param request     聊天请求
     * @param httpRequest HTTP 请求（用于提取元数据）
     * @return 网关响应
     */
    public GatewayResponse processMessage(ChatRequest request, HttpServletRequest httpRequest) {
        var message = buildChatGatewayMessage(request, httpRequest, false);
        return submitSync(message);
    }

    /**
     * 流式处理消息（Controller 调用，返回 streamId 用于关联 SseEmitter）。
     *
     * @param request     聊天请求
     * @param httpRequest HTTP 请求（用于提取元数据）
     * @return 网关响应
     */
    public GatewayResponse processMessageStreaming(ChatRequest request, HttpServletRequest httpRequest) {
        var message = buildChatGatewayMessage(request, httpRequest, true);
        return submitSync(message);
    }

    /**
     * 处理 A2UI 信号回传。
     *
     * @param request     信号请求
     * @param httpRequest HTTP 请求（用于提取元数据）
     * @return 网关响应
     */
    public GatewayResponse processSignal(SignalRequest request, HttpServletRequest httpRequest) {
        var message = buildSignalGatewayMessage(request, httpRequest);
        return submitSync(message);
    }

    // ── 内部构建方法 ──────────────────────────────────────────

    /**
     * 从 ChatRequest 构建 GatewayMessage。
     *
     * @param request     聊天请求
     * @param httpRequest HTTP 请求（可为 null，normalize 场景）
     * @param acceptsSse  是否接受 SSE 流式响应
     * @return 标准化后的网关消息
     */
    private GatewayMessage buildChatGatewayMessage(ChatRequest request,
                                                    HttpServletRequest httpRequest,
                                                    boolean acceptsSse) {
        var sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();

        // Phase 2：根据 attachmentIds 加载消息附件（二进制 + MIME）
        List<GatewayMessage.Attachment> attachments = loadAttachments(request, sessionId);

        // Phase 3：音频附件自动转录
        String messageContent = request.content();
        messageContent = transcribeAudioAttachments(attachments, messageContent, sessionId);

        var content = new MessageContent.TextMessage(messageContent);

        return GatewayMessage.builder()
                .channelType(ChannelType.WEB)
                .userId(DEFAULT_WEB_USER)
                .sessionId(sessionId)
                .content(content)
                .attachments(attachments)
                .channelMetadata(buildWebMetadata(httpRequest, acceptsSse, request.preferredProvider()))
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 从 SignalRequest 构建 GatewayMessage。
     *
     * @param request     信号请求
     * @param httpRequest HTTP 请求（可为 null，normalize 场景）
     * @return 标准化后的网关消息
     */
    private GatewayMessage buildSignalGatewayMessage(SignalRequest request,
                                                      HttpServletRequest httpRequest) {
        var payload = Map.<String, Object>of(
                "name", request.name(),
                "payload", request.payload()
        );
        var content = new MessageContent.EventMessage(A2UI_SIGNAL_EVENT_TYPE, payload);
        return GatewayMessage.builder()
                .channelType(ChannelType.WEB)
                .userId(DEFAULT_WEB_USER)
                .sessionId(request.sessionId())
                .content(content)
                .channelMetadata(buildWebMetadata(httpRequest, false, null))
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 从 HttpServletRequest 构建 WebMetadata。
     *
     * @param httpRequest       HTTP 请求（可为 null）
     * @param acceptsSse        是否接受 SSE 流式响应
     * @param preferredProvider 会话级偏好 LLM Provider（可为 null）
     * @return Web 通道元数据
     */
    private ChannelMetadata.WebMetadata buildWebMetadata(HttpServletRequest httpRequest,
                                                          boolean acceptsSse,
                                                          String preferredProvider) {
        if (httpRequest == null) {
            return new ChannelMetadata.WebMetadata("unknown", "unknown", null, acceptsSse, preferredProvider);
        }
        var userAgent = httpRequest.getHeader("User-Agent");
        return new ChannelMetadata.WebMetadata(
                userAgent != null ? userAgent : "unknown",
                httpRequest.getRemoteAddr(),
                null,
                acceptsSse,
                preferredProvider
        );
    }

    /**
     * 检测音频附件并自动转录为文本。
     *
     * <p>当附件 MIME 类型以 {@code audio/} 开头且 {@link AudioTranscriber} 可用时，
     * 调用转录器将音频转为文本，替换或补充原始消息内容，并通过 SSE 推送转录结果。</p>
     *
     * @param attachments    附件列表
     * @param originalContent 原始消息内容
     * @param sessionId      会话 ID（用于 SSE 推送）
     * @return 转录后的消息内容（如无音频附件则返回原始内容）
     */
    private String transcribeAudioAttachments(List<GatewayMessage.Attachment> attachments,
                                               String originalContent,
                                               String sessionId) {
        if (attachments.isEmpty()) {
            return originalContent;
        }

        // 原生音频路由启用时，跳过 STT 转录，音频将直接发送给支持 NATIVE_AUDIO 的 Provider
        if (mediaProperties.getNativeAudio().isEnabled()) {
            boolean hasAudio = attachments.stream()
                    .anyMatch(att -> att.mimeType() != null && att.mimeType().startsWith("audio/"));
            if (hasAudio) {
                log.info("原生音频路由已启用，跳过 STT 转录: sessionId={}", sessionId);
                return originalContent;
            }
        }

        if (audioTranscriber == null) {
            return originalContent;
        }

        for (var attachment : attachments) {
            if (attachment.mimeType() == null || !attachment.mimeType().startsWith("audio/")) {
                continue;
            }

            try {
                log.debug("检测到音频附件，开始转录: fileName={}, mimeType={}", attachment.fileName(), attachment.mimeType());
                String transcribedText = audioTranscriber.transcribe(attachment.data(), attachment.mimeType());
                log.info("音频转录成功: fileName={}, 转录文本长度={}", attachment.fileName(), transcribedText.length());

                // 通过 SSE 推送转录结果
                pushTranscriptionEvent(sessionId, transcribedText);

                // 将转录文本作为消息内容（替换占位符或补充原始内容）
                if (originalContent == null || originalContent.isBlank() || "[语音消息]".equals(originalContent)) {
                    return transcribedText;
                }
                return originalContent + "\n\n[语音转录] " + transcribedText;
            } catch (AudioTranscriptionException e) {
                log.warn("音频转录失败: fileName={}, error={}", attachment.fileName(), e.getMessage());
            }
        }

        return originalContent;
    }

    /**
     * 通过 SSE 广播语音转录结果事件到所有通知连接。
     *
     * <p>转录发生在消息构建阶段（尚无 streamId），因此使用通知广播机制推送。
     * 前端通过 notification SSE 连接接收 transcription 事件。</p>
     *
     * @param sessionId       会话 ID
     * @param transcribedText 转录文本
     */
    private void pushTranscriptionEvent(String sessionId, String transcribedText) {
        if (sseSessionManager == null) {
            return;
        }
        try {
            var eventData = Map.of("text", transcribedText, "sessionId", sessionId);
            sseSessionManager.broadcastByPrefix("notification-", SseEventType.TRANSCRIPTION, eventData);
            log.debug("SSE 转录事件广播成功: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("SSE 转录事件广播失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 根据 ChatRequest 中的附件 ID 列表加载附件记录与二进制数据。
     *
     * @param request   聊天请求
     * @param sessionId 会话 ID（用于日志）
     * @return GatewayMessage 附件列表
     */
    private List<GatewayMessage.Attachment> loadAttachments(ChatRequest request, String sessionId) {
        var ids = request.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<GatewayMessage.Attachment> results = new ArrayList<>();
        for (String id : ids) {
            try {
                var record = attachmentRepository.findById(id);
                if (record == null) {
                    log.warn("WebChannelAdapter: 未找到附件记录, id={}, sessionId={}", id, sessionId);
                    continue;
                }
                Path path = Path.of(record.filePath());
                byte[] data = Files.readAllBytes(path);
                var attachment = new GatewayMessage.Attachment(
                        record.id(),
                        record.fileName(),
                        record.mimeType(),
                        data,
                        record.fileSize()
                );
                results.add(attachment);
            } catch (IOException e) {
                log.warn("WebChannelAdapter: 读取附件失败, id={}, sessionId={}, error={}", id, sessionId, e.getMessage());
            } catch (Exception e) {
                log.warn("WebChannelAdapter: 处理附件失败, id={}, sessionId={}, error={}", id, sessionId, e.getMessage());
            }
        }
        return results;
    }
}
