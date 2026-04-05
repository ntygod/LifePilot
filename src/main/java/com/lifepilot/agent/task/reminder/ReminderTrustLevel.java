package com.lifepilot.agent.task.reminder;

/**
 * 信任梯度等级。
 *
 * <p>定义用户对特定候选类型的信任程度，
 * 决定系统可执行的最大自主动作。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public enum ReminderTrustLevel {

    /** 默认：只观察，不主动提醒。 */
    OBSERVE(0),

    /** 可以通知。 */
    NOTIFY(1),

    /** 可以预备执行。 */
    PREPARE(2),

    /** 可以自动执行。 */
    AUTO_EXECUTE(3);

    private final int level;

    ReminderTrustLevel(int level) {
        this.level = level;
    }

    /**
     * 获取数值等级。
     */
    public int level() {
        return level;
    }

    /**
     * 判断当前等级是否不低于目标等级。
     */
    public boolean isAtLeast(ReminderTrustLevel other) {
        return this.level >= other.level;
    }
}
