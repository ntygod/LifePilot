package com.lifepilot.interaction.web.adapter;

import com.lifepilot.config.threadpool.SharedScheduler;
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
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.media.audio.AudioTranscriber;
import com.lifepilot.media.audio.AudioTranscriptionException;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.notification.Urgency;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Web 通道适配器。
 *
 * @author zsg
 * @since 2026-03-25
 */
public class WebChannelAdapter extends AbstractChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebChannelAdapter.class);
    private static final String A2UI_SIGNAL_EVENT_TYPE = "a2ui_signal";
    private static final String DEFAULT_WEB_USER = "web-user";

    private final AttachmentRepository attachmentRepository;
    private final ChatTurnService chatTurnService;
    @Nullable private final SseSessionManager sseSessionManager;
    @Nullable private final AudioTranscriber audioTranscriber;
    private final MediaProperties mediaProperties;

    public WebChannelAdapter(MessageGateway gateway,
                             GatewayProperties properties,
                             AttachmentRepository attachmentRepository,
                             ChatTurnService chatTurnService,
                             @Nullable SseSessionManager sseSessionManager,
                             @Nullable AudioTranscriber audioTranscriber,
                             MediaProperties mediaProperties,
                             SharedScheduler sharedScheduler) {
        super(gateway, properties, sharedScheduler);
        this.attachmentRepository = attachmentRepository;
        this.chatTurnService = chatTurnService;
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
        log.info("Web 通道适配器已启动");
    }

    @Override
    protected void doStop() {
        log.info("Web 通道适配器停止中，SseEmitter 清理由 SseSessionManager 接管");
    }

    @Override
    protected void doSendResponse(String userId, GatewayResponse response) {
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
                        notificationId, typeId, urgency, content, Instant.now().toString());
                sseSessionManager.broadcastNotification(event);
                log.debug("通知 SSE 广播完成: typeId={}, urgency={}", typeId, urgency);
                return;
            } catch (Exception e) {
                log.warn("通知 SSE 广播失败，降级为默认处理: userId={}, error={}", userId, e.getMessage());
            }
        }
        log.debug("Web 通道异步响应: userId={}, statusCode={}", userId, response.statusCode());
    }

    public GatewayResponse processMessage(ChatRequest request, HttpServletRequest httpRequest) {
        return submitSync(buildChatGatewayMessage(request, httpRequest, false));
    }

    public GatewayResponse processMessageStreaming(ChatRequest request, HttpServletRequest httpRequest) {
        return submitSync(buildChatGatewayMessage(request, httpRequest, true));
    }

    public GatewayResponse processSignal(SignalRequest request, HttpServletRequest httpRequest) {
        return submitSync(buildSignalGatewayMessage(request, httpRequest));
    }

    private GatewayMessage buildChatGatewayMessage(ChatRequest request,
                                                   HttpServletRequest httpRequest,
                                                   boolean acceptsSse) {
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        ChatTurnService.ResolvedTurnRequest resolved = chatTurnService.prepare(sessionId, request);
        ChatRequest normalizedRequest = new ChatRequest(
                resolved.turnId(),
                resolved.action(),
                resolved.content(),
                sessionId,
                resolved.attachmentIds(),
                resolved.preferredProvider()
        );

        List<GatewayMessage.Attachment> attachments = loadAttachments(normalizedRequest, sessionId);
        String messageContent = transcribeAudioAttachments(attachments, normalizedRequest.content(), sessionId);
        var content = new MessageContent.TextMessage(messageContent);

        return GatewayMessage.builder()
                .messageId(resolved.turnId())
                .channelType(ChannelType.WEB)
                .userId(DEFAULT_WEB_USER)
                .sessionId(sessionId)
                .content(content)
                .attachments(attachments)
                .channelMetadata(buildWebMetadata(
                        httpRequest,
                        acceptsSse,
                        resolved.preferredProvider(),
                        resolved.turnId(),
                        resolved.action()))
                .timestamp(Instant.now())
                .build();
    }

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
                .channelMetadata(buildWebMetadata(httpRequest, false, null, null, null))
                .timestamp(Instant.now())
                .build();
    }

    private ChannelMetadata.WebMetadata buildWebMetadata(HttpServletRequest httpRequest,
                                                         boolean acceptsSse,
                                                         @Nullable String preferredProvider,
                                                         @Nullable String turnId,
                                                         @Nullable com.lifepilot.interaction.web.model.ChatTurnAction action) {
        if (httpRequest == null) {
            return new ChannelMetadata.WebMetadata(
                    "unknown", "unknown", null, acceptsSse, preferredProvider, turnId, action);
        }
        var userAgent = httpRequest.getHeader("User-Agent");
        return new ChannelMetadata.WebMetadata(
                userAgent != null ? userAgent : "unknown",
                httpRequest.getRemoteAddr(),
                null,
                acceptsSse,
                preferredProvider,
                turnId,
                action
        );
    }

    private String transcribeAudioAttachments(List<GatewayMessage.Attachment> attachments,
                                              String originalContent,
                                              String sessionId) {
        if (attachments.isEmpty()) {
            return originalContent;
        }
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
                log.debug("检测到音频附件，开始转录: fileName={}, mimeType={}",
                        attachment.fileName(), attachment.mimeType());
                String transcribedText = audioTranscriber.transcribe(attachment.data(), attachment.mimeType());
                log.info("音频转录成功: fileName={}, textLength={}",
                        attachment.fileName(), transcribedText.length());
                pushTranscriptionEvent(sessionId, transcribedText);
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
                results.add(new GatewayMessage.Attachment(
                        record.id(),
                        record.fileName(),
                        record.mimeType(),
                        data,
                        record.fileSize()
                ));
            } catch (IOException e) {
                log.warn("WebChannelAdapter: 读取附件失败, id={}, sessionId={}, error={}",
                        id, sessionId, e.getMessage());
            } catch (Exception e) {
                log.warn("WebChannelAdapter: 处理附件失败, id={}, sessionId={}, error={}",
                        id, sessionId, e.getMessage());
            }
        }
        return results;
    }
}
