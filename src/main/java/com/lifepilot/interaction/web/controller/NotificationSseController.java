package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.notification.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 通知 SSE 控制器 — 提供通知专用 SSE 流端点。
 *
 * <p>创建 notification- 前缀的 SseEmitter，连接建立后推送初始未读数快照，
 * 后续由 {@link SseSessionManager} 广播实时通知事件。</p>
 *
 * @author zsg
 * @since 2026-03-15
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationSseController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseController.class);

    private final SseSessionManager sseSessionManager;
    private final NotificationRepository notificationRepository;

    public NotificationSseController(SseSessionManager sseSessionManager,
                                     NotificationRepository notificationRepository) {
        this.sseSessionManager = sseSessionManager;
        this.notificationRepository = notificationRepository;
    }

    /**
     * 通知 SSE 流端点。
     *
     * <p>创建 notification-{uuid} 前缀的 emitter，推送初始未读数快照后保持连接。
     * 后续通知事件由 {@link SseSessionManager#broadcastNotification(Object)} 广播。</p>
     *
     * @param userId 用户 ID
     * @return SSE emitter
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter notificationStream(@RequestParam String userId) {
        String streamId = "notification-" + UUID.randomUUID();

        // 通知 SSE 使用无限超时（0），依靠心跳保活，避免周期性超时重连
        var emitter = sseSessionManager.createNotificationEmitter(streamId, 0L);
        log.info("通知 SSE 连接已建立: streamId={}, userId={}", streamId, userId);

        // 推送初始未读数快照
        try {
            long unreadCount = notificationRepository.countUnreadByUserId(userId);
            var snapshot = SseEmitter.event()
                    .name(SseEventType.NOTIFICATION)
                    .data(Map.of("type", "unread-count-snapshot", "unreadCount", unreadCount));
            emitter.send(snapshot);
            log.debug("初始未读数快照已推送: userId={}, unreadCount={}", userId, unreadCount);
        } catch (IOException e) {
            log.warn("推送初始未读数快照失败: userId={}, streamId={}", userId, streamId, e);
        } catch (Exception e) {
            log.warn("查询未读数失败，跳过初始快照推送: userId={}", userId, e);
        }

        return emitter;
    }
}
