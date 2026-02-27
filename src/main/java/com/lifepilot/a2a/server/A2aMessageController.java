package com.lifepilot.a2a.server;

import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.A2aTask;
import com.lifepilot.a2a.model.A2aMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * A2A 消息处理 REST 端点。
 *
 * <p>提供同步消息发送和 SSE 流式消息处理两个端点。
 * streaming-enabled=false 时 stream 端点返回 HTTP 405。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api/a2a")
public class A2aMessageController {

    private static final Logger log = LoggerFactory.getLogger(A2aMessageController.class);

    private final A2aAgentExecutor executor;
    private final A2aProperties properties;

    public A2aMessageController(A2aAgentExecutor executor, A2aProperties properties) {
        this.executor = executor;
        this.properties = properties;
    }

    /**
     * 同步消息处理。
     *
     * @param message A2A 消息
     * @param skillId 目标 Skill ID（可空）
     * @return 执行完成的 A2aTask
     */
    @PostMapping(value = "/message/send", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<A2aTask> sendMessage(@RequestBody A2aMessage message,
                                               @RequestParam(required = false) @Nullable String skillId) {
        log.info("收到 A2A 同步消息: messageId={}, skillId={}", message.messageId(), skillId);
        try {
            A2aTask task = executor.execute(message, skillId);
            return ResponseEntity.ok(task);
        } catch (Exception e) {
            log.error("A2A 消息处理异常: messageId={}", message.messageId(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * SSE 流式消息处理。
     *
     * <p>streaming-enabled=false 时返回 HTTP 405 Method Not Allowed。
     * SSE 事件类型：task-status-update / task-artifact-update / task-complete。</p>
     *
     * @param message A2A 消息
     * @param skillId 目标 Skill ID（可空）
     * @return SseEmitter 用于流式推送 Task 状态更新
     */
    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> streamMessage(@RequestBody A2aMessage message,
                                           @RequestParam(required = false) @Nullable String skillId) {
        if (!properties.getServer().isStreamingEnabled()) {
            log.warn("流式端点已禁用，返回 405: messageId={}", message.messageId());
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(Map.of("error", Map.of("code", 405, "message", "流式消息处理已禁用")));
        }

        log.info("收到 A2A 流式消息: messageId={}, skillId={}", message.messageId(), skillId);
        var emitter = new SseEmitter(60_000L);

        executor.executeStreaming(message, skillId, task -> {
            try {
                String eventType = task.status().state().isTerminal()
                        ? "task-complete"
                        : (task.artifacts() != null && !task.artifacts().isEmpty())
                                ? "task-artifact-update"
                                : "task-status-update";

                var event = SseEmitter.event()
                        .name(eventType)
                        .data(task);
                emitter.send(event);

                // 终态时关闭 SSE 连接
                if (task.status().state().isTerminal()) {
                    emitter.complete();
                }
            } catch (IOException e) {
                log.warn("SSE 发送事件失败: taskId={}", task.id(), e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> log.info("A2A SSE 超时: messageId={}", message.messageId()));
        emitter.onError(ex -> log.warn("A2A SSE 异常: messageId={}", message.messageId(), ex));

        return ResponseEntity.ok(emitter);
    }
}
