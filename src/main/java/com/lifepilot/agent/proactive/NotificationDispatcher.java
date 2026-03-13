package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.agent.proactive.model.InitiativeType;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.notification.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 通知分发器 — 根据 {@link InitiativeType} 和紧急程度选择分发策略。
 *
 * <ul>
 *   <li>{@link InitiativeType#NOTIFICATION}：HIGH/MEDIUM → 遍历所有通道广播，LOW → 被动队列</li>
 *   <li>{@link InitiativeType#PASSIVE_HINT}：直接入队被动队列，等待用户下次交互时附带展示</li>
 * </ul>
 *
 * <p>同时持久化到 proactive_notifications 表。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels;
    private final PassiveNotificationQueue passiveQueue;
    private final JdbcTemplate jdbcTemplate;

    public NotificationDispatcher(List<NotificationChannel> channels,
                                   PassiveNotificationQueue passiveQueue,
                                   JdbcTemplate jdbcTemplate) {
        this.channels = List.copyOf(channels);
        this.passiveQueue = passiveQueue;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 分发通知。
     *
     * @param notification   通知
     * @param initiativeType 主动介入类型
     */
    public void dispatch(ProactiveNotification notification, InitiativeType initiativeType) {
        // PASSIVE_HINT → 直接入队被动队列
        if (initiativeType == InitiativeType.PASSIVE_HINT) {
            passiveQueue.enqueue(notification);
            log.info("被动提示入队: typeId={}", notification.typeId());
            persistNotification(notification);
            return;
        }

        // NOTIFICATION → 根据紧急程度路由
        if (notification.urgency() == Urgency.LOW) {
            passiveQueue.enqueue(notification);
            log.info("通知入队被动队列: typeId={}, urgency={}", notification.typeId(), notification.urgency());
        } else {
            // HIGH/MEDIUM 遍历所有通道分发，单通道失败不中断
            for (NotificationChannel channel : channels) {
                try {
                    channel.send(notification);
                    log.info("通知已发送: typeId={}, urgency={}, channel={}",
                            notification.typeId(), notification.urgency(), channel.id());
                } catch (Exception e) {
                    log.warn("通知发送失败: channel={}, typeId={}, error={}",
                            channel.id(), notification.typeId(), e.getMessage());
                }
            }
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
                    notification.typeId(),
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
