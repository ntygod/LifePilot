package com.lifepilot.interaction.tray;

import java.awt.TrayIcon;

import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.model.ProactiveNotification;
import com.lifepilot.agent.proactive.model.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 系统托盘通知通道 — 通过操作系统原生通知推送提醒。
 *
 * <p>HIGH → WARNING，MEDIUM → INFO，LOW → 不通过托盘显示。
 * 暂停状态下直接返回。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class TrayNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TrayNotificationChannel.class);

    private final TrayManager trayManager;

    public TrayNotificationChannel(TrayManager trayManager) {
        this.trayManager = trayManager;
    }

    @Override
    public String id() {
        return "tray";
    }

    @Override
    public void send(ProactiveNotification notification) {
        // 暂停时直接返回
        if (trayManager.isNotificationPaused()) {
            log.debug("系统托盘通知: 通知已暂停，跳过: type={}", notification.type());
            return;
        }

        // LOW 紧急度不通过托盘显示
        if (notification.urgency() == Urgency.LOW) {
            log.debug("系统托盘通知: LOW 紧急度不通过托盘显示: type={}", notification.type());
            return;
        }

        // 映射 Urgency → MessageType
        TrayIcon.MessageType messageType = switch (notification.urgency()) {
            case HIGH -> TrayIcon.MessageType.WARNING;
            case MEDIUM -> TrayIcon.MessageType.INFO;
            case LOW -> throw new IllegalStateException("LOW 已在上方过滤");
        };

        trayManager.displayNotification("LifePilot", notification.content(), messageType);
        log.info("系统托盘通知: 已发送: type={}, urgency={}", notification.type(), notification.urgency());
    }
}
