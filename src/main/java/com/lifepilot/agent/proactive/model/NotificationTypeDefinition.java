package com.lifepilot.agent.proactive.model;

import java.util.List;

/**
 * 通知类型定义记录 — 描述一种可扩展的通知类型及其元数据。
 *
 * <p>包含类型标识、显示名称、关键词列表和默认冷却时间。
 * {@code keywords} 在构造时通过 {@link List#copyOf(List)} 确保不可变。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public record NotificationTypeDefinition(
        /** 类型标识（snake_case，如 "deadline_reminder"）。 */
        String typeId,

        /** 显示名称（如 "待办截止提醒"）。 */
        String displayName,

        /** 关键词列表，用于 ResponseTracker 判断用户消息相关性。 */
        List<String> keywords,

        /** 默认冷却时间（分钟）。 */
        int defaultCooldownMinutes
) {

    /** 紧凑构造函数 — 确保 keywords 不可变。 */
    public NotificationTypeDefinition {
        keywords = keywords != null ? List.copyOf(keywords) : List.of();
    }
}
