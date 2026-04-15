package com.lifepilot.agent.task.proactive;

/**
 * 四级投递阶梯。
 *
 * <p>SILENT: 仅记录到决策日志。
 * QUEUE: 存储，下次用户主动对话时展示。
 * NOTIFY: 浮窗气泡/桌面通知。
 * INTERRUPT: 主动消息/直接发到渠道。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public enum DeliveryLevel {
    SILENT,
    QUEUE,
    NOTIFY,
    INTERRUPT;

    /** 是否为通知级别（NOTIFY 或 INTERRUPT）— 需要用户关注。 */
    public boolean isNotifiable() {
        return this == NOTIFY || this == INTERRUPT;
    }
}
