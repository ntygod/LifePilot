package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.InteractionTraceHeaders;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.interaction.web.model.ChatRequest;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 浏览器入站组装服务。
 *
 * <p>负责把浏览器 HTTP/SSE 请求组装为统一网关消息，承担 Web 入口的
 * turn 准备、附件装载和音频转写职责。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class BrowserIngressService {

    private static final Logger log = LoggerFactory.getLogger(BrowserIngressService.class);
    private static final String A2UI_SIGNAL_EVENT_TYPE = "a2ui_signal";
    private static final String DEFAULT_WEB_USER = "web-user";
    private static final String WEB_PLATFORM = "web";
    private static final String WEB_INSTANCE_ID = "web.default";

    private final AttachmentRepository attachmentRepository;
    private final ChatTurnService chatTurnService;
    @Nullable private final SseSessionManager sseSessionManager;
    @Nullable private final AudioTranscriber audioTranscriber;
    private final MediaProperties mediaProperties;
    private final BooleanSupplier nativeAudioProbe;

    public BrowserIngressService(AttachmentRepository attachmentRepository,
                                 ChatTurnService chatTurnService,
                                 @Nullable SseSessionManager sseSessionManager,
                                 @Nullable AudioTranscriber audioTranscriber,
                                 MediaProperties mediaProperties,
                                 BooleanSupplier nativeAudioProbe) {
        this.attachmentRepository = attachmentRepository;
        this.chatTurnService = chatTurnService;
        this.sseSessionManager = sseSessionManager;
        this.audioTranscriber = audioTranscriber;
        this.mediaProperties = mediaProperties;
        this.nativeAudioProbe = nativeAudioProbe;
    }

    /**
     * 语音输入能力摘要。
     *
     * @param nativeAudio  是否有原生音频 Provider（如 Qwen3-Omni）
     * @param stt          是否有 STT 转录能力（Whisper CLI 或云端）
     * @param supported    语音输入是否可用（任一为 true 即可）
     */
    public record VoiceCapability(boolean nativeAudio, boolean stt, boolean supported) {}

    /** 查询当前语音输入能力。 */
    public VoiceCapability voiceCapability() {
        boolean nativeAudio = nativeAudioProbe.getAsBoolean();
        boolean stt = audioTranscriber != null && audioTranscriber.isAvailable();
        return new VoiceCapability(nativeAudio, stt, nativeAudio || stt);
    }

    public GatewayMessage buildChatMessage(ChatRequest request,
                                           @Nullable HttpServletRequest httpRequest,
                                           DeliveryMode deliveryMode) {
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

        // 转录成功后移除已转录的音频附件，避免它们作为 mediaContents 触发多模态路由
        boolean audioTranscribed = !messageContent.equals(normalizedRequest.content());
        List<GatewayMessage.Attachment> effectiveAttachments = audioTranscribed
                ? attachments.stream()
                    .filter(att -> att.mimeType() == null || !att.mimeType().startsWith("audio/"))
                    .toList()
                : attachments;

        return GatewayMessage.builder()
                .messageId(resolved.turnId())
                .channelType(ChannelType.WEB)
                .userId(DEFAULT_WEB_USER)
                .sessionId(sessionId)
                .content(content)
                .attachments(effectiveAttachments)
                .channelMetadata(buildWebMetadata(
                        httpRequest,
                        deliveryMode,
                        resolved.preferredProvider(),
                        resolved.turnId(),
                        resolved.action()))
                .timestamp(Instant.now())
                .traceHeaders(buildTraceHeaders(deliveryMode))
                .build();
    }

    public GatewayMessage buildSignalMessage(SignalRequest request,
                                             @Nullable HttpServletRequest httpRequest) {
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
                .channelMetadata(buildWebMetadata(httpRequest, DeliveryMode.SYNC, null, null, null))
                .timestamp(Instant.now())
                .traceHeaders(buildTraceHeaders(DeliveryMode.SYNC))
                .build();
    }

    private ChannelMetadata.WebMetadata buildWebMetadata(@Nullable HttpServletRequest httpRequest,
                                                         DeliveryMode deliveryMode,
                                                         @Nullable String preferredProvider,
                                                         @Nullable String turnId,
                                                         @Nullable com.lifepilot.interaction.web.model.ChatTurnAction action) {
        if (httpRequest == null) {
            return new ChannelMetadata.WebMetadata(
                    "unknown", "unknown", null,
                    deliveryMode == DeliveryMode.SSE_STREAM,
                    preferredProvider, turnId, action);
        }
        var userAgent = httpRequest.getHeader("User-Agent");
        return new ChannelMetadata.WebMetadata(
                userAgent != null ? userAgent : "unknown",
                httpRequest.getRemoteAddr(),
                null,
                deliveryMode == DeliveryMode.SSE_STREAM,
                preferredProvider,
                turnId,
                action
        );
    }

    private Map<String, String> buildTraceHeaders(DeliveryMode deliveryMode) {
        return Map.of(
                InteractionTraceHeaders.DELIVERY_MODE, deliveryMode.name(),
                InteractionTraceHeaders.SOURCE_KIND, SourceKind.CHANNEL.name(),
                InteractionTraceHeaders.SOURCE_ID, WEB_INSTANCE_ID,
                InteractionTraceHeaders.CHANNEL_PLATFORM, WEB_PLATFORM,
                InteractionTraceHeaders.CHANNEL_INSTANCE_ID, WEB_INSTANCE_ID
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
            if (hasAudio && nativeAudioProbe.getAsBoolean()) {
                log.info("原生音频路由已启用且有可用 Provider，跳过 STT 转录: sessionId={}", sessionId);
                return originalContent;
            }
            if (hasAudio) {
                log.info("原生音频路由已启用但无可用 Provider，降级到 STT 转录: sessionId={}", sessionId);
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
                    log.warn("BrowserIngressService: 未找到附件记录, id={}, sessionId={}", id, sessionId);
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
                log.warn("BrowserIngressService: 读取附件失败, id={}, sessionId={}, error={}",
                        id, sessionId, e.getMessage());
            } catch (Exception e) {
                log.warn("BrowserIngressService: 处理附件失败, id={}, sessionId={}, error={}",
                        id, sessionId, e.getMessage());
            }
        }
        return results;
    }
}
