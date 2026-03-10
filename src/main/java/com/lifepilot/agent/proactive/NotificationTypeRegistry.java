package com.lifepilot.agent.proactive;

import com.lifepilot.agent.proactive.model.NotificationTypeDefinition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通知类型注册中心 — 管理可扩展的通知类型定义。
 *
 * <p>替代原有的 {@code NotificationType} 枚举，支持运行时注册新通知类型。
 * 构造时预注册 6 种默认通知类型，与原枚举定义等价。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class NotificationTypeRegistry {

    private final ConcurrentHashMap<String, NotificationTypeDefinition> types = new ConcurrentHashMap<>();

    /** 构造函数 — 预注册默认通知类型。 */
    public NotificationTypeRegistry() {
        registerDefaults();
    }

    /**
     * 注册通知类型定义。
     *
     * @param definition 通知类型定义
     */
    public void register(NotificationTypeDefinition definition) {
        types.put(definition.typeId(), definition);
    }

    /**
     * 按 typeId 查找通知类型定义。
     *
     * @param typeId 类型标识
     * @return 通知类型定义，未找到时返回空
     */
    public Optional<NotificationTypeDefinition> resolve(String typeId) {
        return Optional.ofNullable(types.get(typeId));
    }

    /**
     * 返回所有已注册通知类型定义。
     *
     * @return 不可变的通知类型定义列表
     */
    public List<NotificationTypeDefinition> listAll() {
        return List.copyOf(types.values());
    }

    /** 预注册 6 种默认通知类型。 */
    private void registerDefaults() {
        register(new NotificationTypeDefinition(
                "deadline_reminder", "待办截止提醒",
                List.of("待办", "截止", "到期", "deadline"), 60));
        register(new NotificationTypeDefinition(
                "schedule_reminder", "日程开始提醒",
                List.of("日程", "会议", "安排", "schedule"), 30));
        register(new NotificationTypeDefinition(
                "habit_reminder", "习惯打卡提醒",
                List.of("习惯", "打卡", "habit"), 120));
        register(new NotificationTypeDefinition(
                "streak_at_risk", "连续打卡风险",
                List.of("连续", "打卡", "中断", "streak"), 240));
        register(new NotificationTypeDefinition(
                "daily_summary", "每日总结",
                List.of("总结", "今天", "daily"), 1440));
        register(new NotificationTypeDefinition(
                "weekly_review", "每周回顾",
                List.of("回顾", "本周", "weekly"), 10080));
    }
}
