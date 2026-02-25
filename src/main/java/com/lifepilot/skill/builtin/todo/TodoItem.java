package com.lifepilot.skill.builtin.todo;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 待办事项。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record TodoItem(
        String id,
        String title,
        @Nullable String description,
        Priority priority,
        Status status,
        @Nullable String dueDate,
        @Nullable List<String> tags,
        String createdAt,
        String updatedAt
) {

    /** 待办优先级。 */
    public enum Priority { HIGH, MEDIUM, LOW }

    /** 待办状态。 */
    public enum Status { PENDING, IN_PROGRESS, COMPLETED }

    /**
     * 校验状态转换合法性：PENDING → IN_PROGRESS → COMPLETED。
     *
     * @param target 目标状态
     * @return 转换是否合法
     */
    public boolean canTransitionTo(Status target) {
        return switch (this.status) {
            case PENDING -> target == Status.IN_PROGRESS || target == Status.COMPLETED;
            case IN_PROGRESS -> target == Status.COMPLETED;
            case COMPLETED -> false;
        };
    }
}
