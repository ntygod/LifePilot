package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.meta.infra.shell.ProcessOutputEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * 后台进程输出 SSE 实时推送控制器。
 *
 * <p>客户端通过 {@code /api/processes/stream} 订阅，
 * 当后台进程产生新输出时，通过 {@link ProcessOutputEvent} 监听并广播到所有已连接客户端。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
@RestController
@RequestMapping("/api/processes")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ProcessSseController {

    private static final Logger log = LoggerFactory.getLogger(ProcessSseController.class);
    private static final String STREAM_ID_PREFIX = "process-";
    /** SSE 连接超时（5 分钟）。客户端应实现心跳检测与断线自动重连。 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final SseSessionManager sseSessionManager;

    public ProcessSseController(SseSessionManager sseSessionManager) {
        this.sseSessionManager = sseSessionManager;
    }

    /**
     * SSE 订阅端点 — 实时推送后台进程输出。
     *
     * <p>连接建立后，所有后台进程的 stdout/stderr 输出将通过 process-output 事件推送。</p>
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processStream() {
        String streamId = STREAM_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        var emitter = sseSessionManager.createNotificationEmitter(streamId, SSE_TIMEOUT_MS);
        log.info("后台进程 SSE 订阅建立: streamId={}", streamId);
        return emitter;
    }

    /**
     * 监听后台进程输出事件并广播到所有已订阅的 SSE 客户端。
     *
     * @param event 进程输出事件
     */
    @EventListener
    public void onProcessOutput(ProcessOutputEvent event) {
        String eventType = "state".equals(event.getChannel())
                ? SseEventType.PROCESS_STATE_CHANGE
                : SseEventType.PROCESS_OUTPUT;
        sseSessionManager.broadcastByPrefix(STREAM_ID_PREFIX, eventType, Map.of(
                "sessionId", event.getSessionId(),
                "channel", event.getChannel(),
                "content", event.getContent(),
                "state", event.getState().name()
        ));
    }
}
