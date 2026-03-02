package com.lifepilot.interaction.web.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lifepilot.interaction.channel.AbstractChannelAdapter;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.SignalRequest;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public WebChannelAdapter(MessageGateway gateway,
                             GatewayProperties properties,
                             AttachmentRepository attachmentRepository) {
        super(gateway, properties);
        this.attachmentRepository = attachmentRepository;
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
        var content = new MessageContent.TextMessage(request.content());
        var sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();

        // Phase 2：根据 attachmentIds 加载消息附件（二进制 + MIME）
        List<GatewayMessage.Attachment> attachments = loadAttachments(request, sessionId);

        return GatewayMessage.builder()
                .channelType(ChannelType.WEB)
                .userId(DEFAULT_WEB_USER)
                .sessionId(sessionId)
                .content(content)
                .attachments(attachments)
                .channelMetadata(buildWebMetadata(httpRequest, acceptsSse))
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
                .channelMetadata(buildWebMetadata(httpRequest, false))
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 从 HttpServletRequest 构建 WebMetadata。
     *
     * @param httpRequest HTTP 请求（可为 null）
     * @param acceptsSse  是否接受 SSE 流式响应
     * @return Web 通道元数据
     */
    private ChannelMetadata.WebMetadata buildWebMetadata(HttpServletRequest httpRequest,
                                                          boolean acceptsSse) {
        if (httpRequest == null) {
            return new ChannelMetadata.WebMetadata("unknown", "unknown", null, acceptsSse);
        }
        var userAgent = httpRequest.getHeader("User-Agent");
        return new ChannelMetadata.WebMetadata(
                userAgent != null ? userAgent : "unknown",
                httpRequest.getRemoteAddr(),
                null,
                acceptsSse
        );
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
