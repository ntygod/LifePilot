package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.config.WebProperties;
import com.lifepilot.interaction.web.model.McpStatusChange;
import com.lifepilot.interaction.web.model.McpStatusSnapshot;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.mcp.registry.McpServerStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * MCP Server 状态 SSE 实时推送控制器。
 *
 * <p>提供 SSE 订阅端点，客户端连接后立即推送初始快照，
 * 后续状态变化通过 Spring Event 监听并广播到所有已连接客户端。</p>
 *
 * @author zsg
 * @since 2026-03-11
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpStatusSseController {

    private static final Logger log = LoggerFactory.getLogger(McpStatusSseController.class);
    private static final String STREAM_ID_PREFIX = "mcp-status-";

    private final McpServerRegistry mcpServerRegistry;
    private final SseSessionManager sseSessionManager;
    private final WebProperties webProperties;

    public McpStatusSseController(McpServerRegistry mcpServerRegistry,
                                   SseSessionManager sseSessionManager,
                                   WebProperties webProperties) {
        this.mcpServerRegistry = mcpServerRegistry;
        this.sseSessionManager = sseSessionManager;
        this.webProperties = webProperties;
    }

    /**
     * SSE 订阅端点 — 实时推送 MCP Server 状态变化。
     *
     * <p>连接建立后立即推送初始快照（mcp-status-snapshot），
     * 后续状态变化通过 mcp-status-change 事件推送。</p>
     *
     * @return SSE 事件流
     */
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream() {
        var streamId = STREAM_ID_PREFIX + UUID.randomUUID();
        var timeout = webProperties.sse().mcpStatusTimeout();
        var emitter = sseSessionManager.createNotificationEmitter(streamId, timeout);

        // 构建并推送初始快照
        var snapshot = buildSnapshot();
        sseSessionManager.sendEvent(streamId, SseEventType.MCP_STATUS_SNAPSHOT, snapshot);

        log.info("MCP 状态 SSE 连接建立: streamId={}, servers={}", streamId, snapshot.servers().size());
        return emitter;
    }

    /**
     * 监听 {@link McpServerStateChangedEvent}，广播到所有 MCP 状态 SSE 客户端。
     *
     * @param event MCP Server 状态变化事件
     */
    @EventListener
    public void onMcpServerStateChanged(McpServerStateChangedEvent event) {
        var change = new McpStatusChange(
                event.serverName(),
                event.oldState(),
                event.newState(),
                event.timestamp(),
                event.error()
        );
        sseSessionManager.broadcastByPrefix(STREAM_ID_PREFIX, SseEventType.MCP_STATUS_CHANGE, change);
        log.debug("MCP 状态变化已广播: server={}, {}→{}", event.serverName(), event.oldState(), event.newState());
    }

    /** 构建当前所有 MCP Server 的状态快照。 */
    private McpStatusSnapshot buildSnapshot() {
        var servers = mcpServerRegistry.listServers().stream()
                .map(entry -> new McpStatusSnapshot.ServerStatus(
                        entry.config().name(),
                        entry.state(),
                        entry.connectedSince(),
                        entry.lastError()
                ))
                .toList();
        return new McpStatusSnapshot(servers);
    }
}
