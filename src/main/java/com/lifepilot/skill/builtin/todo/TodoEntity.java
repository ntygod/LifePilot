package com.lifepilot.skill.builtin.todo;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 待办事项实体 — 纯数据载体，序列化为 DataStore Document JSON。
 *
 * <p>替代原 {@link TodoItem} 中的数据库映射逻辑，
 * 通过 {@link com.lifepilot.datastore.adapter.DataStoreCrudAdapter} 存储到 DataStore。</p>
 *
 * @param title       待办标题
 * @param status      状态（PENDING / IN_PROGRESS / COMPLETED）
 * @param priority    优先级（HIGH / MEDIUM / LOW）
 * @param dueDate     截止日期 ISO 8601（可选）
 * @param description 待办描述（可选）
 * @param tags        标签列表（可选）
 * @author zsg
 * @since 2026-03-18
 */
public record TodoEntity(
        String title,
        String status,
        String priority,
        @Nullable String dueDate,
        @Nullable String description,
        @Nullable List<String> tags
) {
}
