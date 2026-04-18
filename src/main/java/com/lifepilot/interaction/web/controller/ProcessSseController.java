package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.meta.infra.shell.BackgroundProcessManager;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 后台进程输出 SSE 实时推送控制器。
 *
 * <p>连接建立后立即推送 {@code process-snapshot}（当前所有活跃进程），
 * 后续按事件类型分流推送：启动（process-started，带 command）、
 * 状态变化（process-state-change）、增量输出（process-output）。</p>
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
    private final BackgroundProcessManager backgroundProcessManager;

    public ProcessSseController(SseSessionManager sseSessionManager,
                                 BackgroundProcessManager backgroundProcessManager) {
        this.sseSessionManager = sseSessionManager;
        this.backgroundProcessManager = backgroundProcessManager;
    }

    /**
     * SSE 订阅端点 — 实时推送后台进程输出与状态变化。
     *
     * <p>连接建立后立即推送初始快照（process-snapshot），包含当前所有活跃进程。
     * 后续事件按类型分流：process-started / process-state-change / process-output。</p>
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processStream() {
        String streamId = STREAM_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        var emitter = sseSessionManager.createNotificationEmitter(streamId, SSE_TIMEOUT_MS);

        // 连接建立时立即推送当前活跃进程快照（支持浏览器刷新恢复）
        var snapshot = buildSnapshot();
        sseSessionManager.sendEvent(streamId, SseEventType.PROCESS_SNAPSHOT, snapshot);

        log.info("后台进程 SSE 订阅建立: streamId={}, snapshotSize={}",
                streamId, snapshot.get("processes") instanceof List<?> list ? list.size() : 0);
        return emitter;
    }

    /**
     * 监听后台进程事件并按类型广播到所有已订阅的 SSE 客户端。
     *
     * <p>分流规则：</p>
     * <ul>
     *   <li>{@code channel=state, content=started} → PROCESS_STARTED（payload 含 command）</li>
     *   <li>{@code channel=state} 其他 → PROCESS_STATE_CHANGE</li>
     *   <li>{@code channel=stdout|stderr} → PROCESS_OUTPUT</li>
     * </ul>
     *
     * @param event 进程输出事件
     */
    @EventListener
    public void onProcessOutput(ProcessOutputEvent event) {
        boolean isStartEvent = "state".equals(event.getChannel())
                && "started".equals(event.getContent());
        String eventType;
        if (isStartEvent) {
            eventType = SseEventType.PROCESS_STARTED;
        } else if ("state".equals(event.getChannel())) {
            eventType = SseEventType.PROCESS_STATE_CHANGE;
        } else {
            eventType = SseEventType.PROCESS_OUTPUT;
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", event.getSessionId());
        payload.put("channel", event.getChannel());
        payload.put("content", event.getContent());
        payload.put("state", event.getState().name());
        if (event.getCommand() != null) {
            payload.put("command", event.getCommand());
        }

        sseSessionManager.broadcastByPrefix(STREAM_ID_PREFIX, eventType, Map.copyOf(payload));
    }

    /** 构建当前所有活跃进程的快照。 */
    private Map<String, Object> buildSnapshot() {
        var processes = backgroundProcessManager.listProcesses().stream()
                .map(p -> {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("sessionId", p.sessionId());
                    entry.put("command", p.command());
                    entry.put("state", p.state().name());
                    if (p.exitCode() != null) entry.put("exitCode", p.exitCode());
                    entry.put("startTime", p.startTime().toString());
                    entry.put("workDir", p.workDir());
                    return (Map<String, Object>) Map.copyOf(entry);
                })
                .toList();
        return Map.of("processes", processes);
    }
}
