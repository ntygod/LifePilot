package com.lifepilot.agent.task.reminder;

/**
 * 提醒跳过原因枚举。
 *
 * <p>用于决策引擎和策略选择器之间的结构化通信，
 * 避免通过中文字符串字面量耦合导致的静默破坏风险。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public enum ReminderSkipReason {

    /** 主题已被用户静默 */
    TOPIC_MUTED("主题已静默"),

    /** 当前处于静默时段 */
    QUIET_HOURS("当前处于静默时段"),

    /** 同主题仍在冷却期 */
    COOLDOWN("同主题仍在冷却期"),

    /** 候选得分不足 */
    LOW_SCORE("候选得分不足"),

    /** 已达到今日主动提醒上限 */
    DAILY_LIMIT("已达到今日主动提醒上限"),

    /** 用户正在全屏使用应用 */
    FULLSCREEN_APP("用户正在全屏使用应用"),

    /** 信任等级不足 */
    TRUST_LEVEL_INSUFFICIENT("信任等级不足"),

    /** 当前没有足够理由打扰用户 */
    INSUFFICIENT_REASON("当前没有足够理由打扰用户");

    private final String label;

    ReminderSkipReason(String label) {
        this.label = label;
    }

    /**
     * 返回面向用户的中文展示文案。
     */
    public String label() {
        return label;
    }

    /**
     * 判断是否可被机会策略选择器提升为轻提醒。
     *
     * <p>仅得分不足和理由不足的跳过可以被 Bandit 策略翻转，
     * 静默、冷却、每日上限等硬约束不允许提升。</p>
     */
    public boolean isPromotable() {
        return this == LOW_SCORE || this == INSUFFICIENT_REASON;
    }
}
