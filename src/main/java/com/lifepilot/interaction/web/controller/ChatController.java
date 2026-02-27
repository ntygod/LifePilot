package com.lifepilot.interaction.web.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.web.adapter.WebChannelAdapter;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatResponse;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.MessageInfo;
import com.lifepilot.interaction.web.model.SessionInfo;
import com.lifepilot.interaction.web.model.SignalRequest;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话 REST + SSE 端点，处理消息发送、会话管理和 A2UI 信号回传。
 *
 * <p>非流式端点通过 {@link WebChannelAdapter#processMessage} 同步处理，
 * 流式端点通过 {@link WebChannelAdapter#processMessageStreaming} 获取 streamId
 * 后由 {@link SseSessionManager} 管理 SseEmitter 生命周期。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final WebChannelAdapter adapter;
    private final SseSessionManager sseManager;

    public ChatController(WebChannelAdapter adapter, SseSessionManager sseManager) {
        this.adapter = adapter;
        this.sseManager = sseManager;
    }

    /**
     * 非流式发送消息。
     *
     * <p>将消息提交到 MessageGateway 中间件管道，同步等待响应后返回完整结果。
     *
     * @param request     聊天请求（content 不可为空）
     * @param httpRequest HTTP 请求
     * @return 包含 messageId、content、a2ui、tokenUsage 的响应
     */
    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(@RequestBody ChatRequest request,
                                         HttpServletRequest httpRequest) {
        if (request.content() == null || request.content().isBlank()) {
            log.warn("非流式消息请求内容为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "消息内容不能为空", Instant.now()));
        }

        log.debug("收到非流式消息请求: sessionId={}", request.sessionId());
        var response = adapter.processMessage(request, httpRequest);
        var chatResponse = toChatResponse(response);
        return ResponseEntity.ok(chatResponse);
    }

    /**
     * SSE 流式发送消息。
     *
     * <p>将消息提交到 MessageGateway，若响应为 {@link ResponseContent.StreamingContent}
     * 则通过 {@link SseSessionManager} 创建 SseEmitter 返回给客户端；
     * 否则创建临时 SseEmitter 发送 done 事件后立即完成。
     *
     * @param request     聊天请求（content 不可为空）
     * @param httpRequest HTTP 请求
     * @return SseEmitter 用于流式推送事件
     */
    @PostMapping("/messages/stream")
    public SseEmitter sendMessageStream(@RequestBody ChatRequest request,
                                         HttpServletRequest httpRequest) {
        if (request.content() == null || request.content().isBlank()) {
            log.warn("流式消息请求内容为空");
            var emitter = new SseEmitter(0L);
            try {
                var errorEvent = SseEmitter.event()
                        .name("error")
                        .data(Map.of("code", 400, "message", "消息内容不能为空"));
                emitter.send(errorEvent);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        log.debug("收到流式消息请求: sessionId={}", request.sessionId());
        var response = adapter.processMessageStreaming(request, httpRequest);

        if (response.content() instanceof ResponseContent.StreamingContent streaming) {
            // 流式响应：通过 SseSessionManager 创建 SseEmitter
            var streamId = streaming.streamId();
            log.debug("创建 SSE 流: streamId={}", streamId);
            return sseManager.createEmitter(streamId);
        }

        // 非流式响应：创建临时 SseEmitter，发送 done 事件后完成
        log.debug("流式端点收到非流式响应，发送 done 事件后关闭");
        var emitter = new SseEmitter(0L);
        try {
            var text = response.content() != null ? response.content().toPlainText() : "";
            var doneData = Map.of(
                    "messageId", response.responseId(),
                    "content", text
            );
            var doneEvent = SseEmitter.event()
                    .name("done")
                    .data(doneData);
            emitter.send(doneEvent);
            emitter.complete();
        } catch (Exception e) {
            log.warn("发送 done 事件失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 获取会话列表。
     *
     * <p>待集成会话持久化层后返回真实数据，当前返回空列表。</p>
     *
     * @return 会话摘要列表
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionInfo>> listSessions() {
        log.debug("获取会话列表");
        return ResponseEntity.ok(List.of());
    }

    /**
     * 获取指定会话的历史消息。
     *
     * <p>待集成会话持久化层后返回真实数据，当前返回空列表。</p>
     *
     * @param id 会话 ID
     * @return 消息摘要列表
     */
    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<MessageInfo>> getSessionMessages(@PathVariable String id) {
        log.debug("获取会话历史消息: sessionId={}", id);
        return ResponseEntity.ok(List.of());
    }

    /**
     * 删除指定会话。
     *
     * <p>待集成会话持久化层后执行真实删除，当前直接返回 204。</p>
     *
     * @param id 会话 ID
     * @return 204 No Content
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        log.debug("删除会话: sessionId={}", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * A2UI 信号回传。
     *
     * <p>将用户与 A2UI 组件的交互信号转换为 GatewayMessage 提交到中间件管道处理。
     *
     * @param request     信号请求（name 和 sessionId 不可为空）
     * @param httpRequest HTTP 请求
     * @return Agent 处理信号后的响应
     */
    @PostMapping("/signals")
    public ResponseEntity<?> handleSignal(@RequestBody SignalRequest request,
                                           HttpServletRequest httpRequest) {
        if (request.name() == null || request.name().isBlank()) {
            log.warn("信号名称为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "信号名称不能为空", Instant.now()));
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            log.warn("信号会话 ID 为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "会话 ID 不能为空", Instant.now()));
        }

        log.debug("收到 A2UI 信号: name={}, sessionId={}", request.name(), request.sessionId());
        var response = adapter.processSignal(request, httpRequest);

        var text = response.content() != null ? response.content().toPlainText() : "";
        return ResponseEntity.ok(Map.of(
                "responseId", response.responseId(),
                "content", text
        ));
    }

    // ── 内部辅助方法 ──────────────────────────────────────────

    /**
     * 将 GatewayResponse 转换为 ChatResponse。
     *
     * @param response 网关响应
     * @return 对话响应
     */
    private ChatResponse toChatResponse(GatewayResponse response) {
        var text = switch (response.content()) {
            case ResponseContent.TextContent tc -> tc.text();
            case ResponseContent.MarkdownContent mc -> mc.markdown();
            case ResponseContent.CardContent cc -> cc.toPlainText();
            case ResponseContent.StreamingContent sc -> sc.toPlainText();
            case null -> "";
        };
        return new ChatResponse(
                response.responseId(),
                text,
                null,
                response.tokenUsage()
        );
    }
}
