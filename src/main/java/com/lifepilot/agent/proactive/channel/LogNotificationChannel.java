package com.lifepilot.agent.proactive.channel;

import com.lifepilot.agent.proactive.model.ProactiveNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志通知通道 — 占位实现，以 INFO 级别日志输出通知内容。
 *
 * <p>未来替换为系统托盘/Web UI/企微等真实通道。</p>
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
