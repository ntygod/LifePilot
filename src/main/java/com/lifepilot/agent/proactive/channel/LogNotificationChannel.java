package com.lifepilot.agent.proactive.channel;

import com.lifepilot.agent.proactive.model.ProactiveNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志通知通道 — 以 INFO 级别日志输出通知内容。
 *
 * <p>作为默认通知通道，与 TrayNotificationChannel 等真实通道并存。
 * 在无 GUI 环境下提供基本的通知输出能力。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public String id() {
        return "log";
    }

    @Override
    public void send(ProactiveNotification notification) {
        log.info("主动提醒 [{}][{}]: {}",
                notification.type(), notification.urgency(), notification.content());
    }
}
