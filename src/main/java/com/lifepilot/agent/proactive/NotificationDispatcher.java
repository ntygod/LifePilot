package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.agent.proactive.model.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 通知分发器 — 根据紧急程度选择通道并发送。
 *
 * <p>HIGH/MEDIUM → 日志通道，LOW → 被动队列。
 * 同时持久化到 proactive_notifications 表。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationChannel logChannel;
    private final PassiveNotificationQueue passiveQueue;
    private final JdbcTemplate jdbcTemplate;

    public NotificationDispatcher(NotificationChannel logChannel,
                                   PassiveNotificationQueue passiveQueue,
                                   JdbcTemplate jdbcTemplate) {
        this.logChannel = logChannel;
        this.passiveQueue = passiveQueue;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 分发通知。
     *
     * @param notification 通知
     */
    public void dispatch(ProactiveNotification notification) {
        // 根据紧急程度路由
        if (notification.urgency() == Urgency.LOW) {
            passiveQueue.enqueue(notification);
            log.info("通知入队被动队列: type={}, urgency={}", notification.type(), notification.urgency());
        } else {
            logChannel.send(notification);
            log.info("通知已发送: type={}, urgency={}, channel={}",
                    notification.type(), notification.urgency(), logChannel.id());
        }

        // 持久化
        persistNotification(notification);
    }

    /** 持久化通知记录。失败时仅记录 WARN 日志。 */
    private void persistNotification(ProactiveNotification notification) {
        try {
            jdbcTemplate.update("""
                INSERT INTO proactive_notifications (id, notification_type, urgency, content, channel, response_status, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                    notification.id(),
                    notification.type().name(),
                    notification.urgency().name(),
                    notification.content(),
                    notification.channel(),
                    notification.responseStatus(),
                    notification.sentAt().toString());
        } catch (Exception e) {
            log.warn("通知持久化失败: id={}, error={}", notification.id(), e.getMessage());
        }
    }
}
