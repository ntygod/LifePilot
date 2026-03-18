package com.lifepilot.notification;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.notification.config.NotificationProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 被动通知定时 drain 调度器。
 *
 * <p>使用 {@link ScheduledExecutorService} 按配置间隔定时调用
 * {@link PassiveNotificationQueue#drainAll()}，并通过
 * {@link SseSessionManager#broadcastNotification(Object)} 广播给 SSE 连接。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final PassiveNotificationQueue passiveNotificationQueue;
    @Nullable
    private final SseSessionManager sseSessionManager;
    private final NotificationProperties properties;
    private final ScheduledExecutorService scheduler;

    public NotificationScheduler(PassiveNotificationQueue passiveNotificationQueue,
                                 @Nullable SseSessionManager sseSessionManager,
                                 NotificationProperties properties,
                                 SharedScheduler sharedScheduler) {
        this.passiveNotificationQueue = passiveNotificationQueue;
        this.sseSessionManager = sseSessionManager;
        this.properties = properties;
        this.scheduler = sharedScheduler.cleanup();
    }

    /**
     * 启动定时 drain 调度。
     */
    @PostConstruct
    public void start() {
        long intervalSeconds = properties.getPassiveDrainInterval();
        scheduler.scheduleWithFixedDelay(this::drain, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("通知调度器已启动: drainInterval={}s", intervalSeconds);
    }

    /**
     * 执行一次 drain 并广播。
     */
    void drain() {
        try {
            var entries = passiveNotificationQueue.drainAll();
            if (entries.isEmpty()) {
                return;
            }
            if (sseSessionManager == null) {
                log.debug("SseSessionManager 不可用，跳过被动通知广播: count={}", entries.size());
                return;
            }
            for (var entry : entries) {
                try {
                    sseSessionManager.broadcastNotification(entry);
                } catch (Exception e) {
                    log.warn("被动通知广播失败: id={}, userId={}", entry.id(), entry.userId(), e);
                }
            }
            log.debug("被动通知广播完成: count={}", entries.size());
        } catch (Exception e) {
            log.warn("被动通知 drain 执行异常", e);
        }
    }


}
