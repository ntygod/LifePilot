package com.lifepilot.agent.proactive.channel;

import com.lifepilot.agent.proactive.model.ProactiveNotification;

/**
 * 通知通道接口 — 扩展点，未来 Gateway 模块实现真实通道。
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface NotificationChannel {

    /** 通道标识。 */
    String id();

    /** 发送通知。 */
    void send(ProactiveNotification notification);
}
