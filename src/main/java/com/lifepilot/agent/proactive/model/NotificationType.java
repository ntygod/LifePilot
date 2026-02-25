package com.lifepilot.agent.proactive.model;

import java.util.List;

/**
 * 提醒类型枚举。
 *
 * <p>每个类型关联一组中文关键词，用于 ResponseTracker 判断用户消息与通知的相关性。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum NotificationType {

    /** 待办截止提醒。 */
    DEADLINE_REMINDER(List.of("待办", "截止", "到期", "deadline")),

    /** 日程开始提醒。 */
    SCHEDULE_REMINDER(List.of("日程", "会议", "安排", "schedule")),

    /** 习惯打卡提醒。 */
    HABIT_REMINDER(List.of("习惯", "打卡", "habit")),

    /** 连续打卡风险提醒。 */
    STREAK_AT_RISK(List.of("连续", "打卡", "中断", "streak")),

    /** 每日总结。 */
    DAILY_SUMMARY(List.of("总结", "今天", "daily")),

    /** 每周回顾。 */
    WEEKLY_REVIEW(List.of("回顾", "本周", "weekly"));

    private final List<String> keywords;

    NotificationType(List<String> keywords) {
        this.keywords = keywords;
    }

    /**
     * 获取该类型关联的关键词列表。
     *
     * @return 不可变关键词列表
     */
    public List<String> keywords() {
        return keywords;
    }
}
