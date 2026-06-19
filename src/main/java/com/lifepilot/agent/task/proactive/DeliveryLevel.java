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

    private static final float QUEUE_THRESHOLD = 0.3f;
    private static final float DEFAULT_NOTIFY_THRESHOLD = 0.5f;
    private static final float DEFAULT_INTERRUPT_THRESHOLD = 0.7f;

    /** 按默认阈值从候选分数推导投递级别。 */
    public static DeliveryLevel fromScore(float score) {
        return fromScore(score, DEFAULT_NOTIFY_THRESHOLD, DEFAULT_INTERRUPT_THRESHOLD);
    }

    /** 按指定通知/打断阈值从候选分数推导投递级别。 */
    public static DeliveryLevel fromScore(float score, float notifyThreshold, float interruptThreshold) {
        if (score >= interruptThreshold) return INTERRUPT;
        if (score >= notifyThreshold) return NOTIFY;
        if (score >= QUEUE_THRESHOLD) return QUEUE;
        return SILENT;
    }

    public static float queueThreshold() {
        return QUEUE_THRESHOLD;
    }

    /** 是否为通知级别（NOTIFY 或 INTERRUPT）— 需要用户关注。 */
    public boolean isNotifiable() {
        return this == NOTIFY || this == INTERRUPT;
    }
}
