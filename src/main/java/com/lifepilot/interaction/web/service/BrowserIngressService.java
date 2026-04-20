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

    /**
     * 文档附件读取提示块起始 sentinel。
     *
     * <p>持久化层（{@code AgentPersistenceHandler}）依据该标记定位并剥离 hint，
     * 避免操作元数据污染 user transcript 在前端历史中回显。模型仍能在 goal
     * 原文中看到 hint，仅在写入 user entry 时移除。</p>
     */
    public static final String DOCUMENT_HINT_BEGIN = "<!--document-parse-hint-begin-->";

    /** 文档附件读取提示块结束 sentinel。 */
    public static final String DOCUMENT_HINT_END = "<!--document-parse-hint-end-->";

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
        var transcription = transcribeAudioAttachments(attachments, normalizedRequest.content(), sessionId);

        // 文档附件提示注入：对 docx/pdf/md/txt 附件，告诉 LLM 可调 file.read(attachmentId=...) 读取内容
        String contentWithDocHint = appendDocumentParseHint(transcription.content(), attachments);
        var content = new MessageContent.TextMessage(contentWithDocHint);

        // 转录成功后移除已转录的音频附件，避免它们作为 mediaContents 触发多模态路由
        List<GatewayMessage.Attachment> effectiveAttachments = transcription.transcribed()
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

    /** 转录结果：内容文本 + 是否实际完成了转录。 */
    private record TranscriptionResult(String content, boolean transcribed) {}

    private TranscriptionResult transcribeAudioAttachments(List<GatewayMessage.Attachment> attachments,
                                                           String originalContent,
                                                           String sessionId) {
        if (attachments.isEmpty()) {
            return new TranscriptionResult(originalContent, false);
        }
        if (mediaProperties.getNativeAudio().isEnabled()) {
            boolean hasAudio = attachments.stream()
                    .anyMatch(att -> att.mimeType() != null && att.mimeType().startsWith("audio/"));
            if (hasAudio && nativeAudioProbe.getAsBoolean()) {
                log.info("原生音频路由已启用且有可用 Provider，跳过 STT 转录: sessionId={}", sessionId);
                return new TranscriptionResult(originalContent, false);
            }
            if (hasAudio) {
                log.info("原生音频路由已启用但无可用 Provider，降级到 STT 转录: sessionId={}", sessionId);
            }
        }
        if (audioTranscriber == null) {
            return new TranscriptionResult(originalContent, false);
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
                String content = (originalContent == null || originalContent.isBlank() || "[语音消息]".equals(originalContent))
                        ? transcribedText
                        : originalContent + "\n\n[语音转录] " + transcribedText;
                return new TranscriptionResult(content, true);
            } catch (AudioTranscriptionException e) {
                log.warn("音频转录失败: fileName={}, error={}", attachment.fileName(), e.getMessage());
            }
        }
        return new TranscriptionResult(originalContent, false);
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

    /**
     * 文档类附件的 MIME 类型前缀/精确匹配集合。
     *
     * <p>这些类型的附件 LLM 无法直接看到内容，需要通过 {@code file.read(attachmentId=...)}
     * 读取文本后再交给模型。图片/音频/视频等多模态附件走各自的专用路由，不在此列。</p>
     *
     * <p>Phase 0 实际可解析集合与 knowledge/parser 对齐：pdf / docx / md / txt / csv。
     * 不包含 {@code application/msword}（.doc，WordParser 仅支持 docx），
     * 也不包含 xlsx/pptx（Phase 1 才会有 parser）。</p>
     */
    private static final List<String> DOCUMENT_MIME_PREFIXES = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown",
            "text/plain",
            "text/csv"
    );

    /**
     * 对包含文档类附件的消息，在末尾追加一段系统提示，告诉 LLM 可调用
     * {@code file.read(attachmentId=...)} 读取附件内容。
     *
     * <p>只要附件列表中存在至少一个 docx/pdf/md/txt 类型的文件就会追加提示；
     * 提示中列出所有文档附件的文件名与 attachmentId，供 LLM 按需选取；
     * 并引导 LLM 对本机已有路径优先使用 {@code file.read(path=...)}。</p>
     *
     * @param originalContent 原始消息文本（可能已含语音转录结果）
     * @param attachments     当前轮次的全部附件
     * @return 追加提示后的文本；若无文档附件则原样返回
     */
    private String appendDocumentParseHint(String originalContent,
                                           List<GatewayMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return originalContent;
        }
        var docs = attachments.stream()
                .filter(this::isDocumentAttachment)
                .toList();
        if (docs.isEmpty()) {
            return originalContent;
        }
        // hint 用 sentinel 标记包裹，AgentPersistenceHandler 在持久化 user entry 前剥离，
        // 避免操作元数据污染 transcript 后被前端历史回显
        var hint = new StringBuilder("\n\n").append(DOCUMENT_HINT_BEGIN)
                .append("\n[系统提示] 用户选择了以下文档附件，可调用 file.read(attachmentId=...) 读取内容。" +
                        "对本机文件优先使用 file.read(path=...)：\n");
        for (var doc : docs) {
            hint.append("- ").append(doc.fileName())
                    .append("（attachmentId=").append(doc.attachmentId()).append("）\n");
        }
        hint.append(DOCUMENT_HINT_END);
        return originalContent + hint;
    }

    /** 判断单个附件是否属于文档类（需要 file.read 工具介入）。 */
    private boolean isDocumentAttachment(GatewayMessage.Attachment att) {
        if (att.mimeType() == null) {
            return false;
        }
        return DOCUMENT_MIME_PREFIXES.stream().anyMatch(att.mimeType()::startsWith);
    }
}
