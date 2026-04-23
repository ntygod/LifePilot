package com.lifepilot.interaction.web.model.scheduled;

import com.lifepilot.agent.task.CronTaskEntry;
import org.springframework.lang.Nullable;

/**
 * 定时任务 API 响应 DTO。
 *
 * <p>字段直接映射 {@link CronTaskEntry}，无枚举转换，createdAt / updatedAt 保持
 * String 类型（ISO 8601，与持久层一致，前端按需解析）。</p>
 *
 * @param id          任务 ID（UUID）
 * @param name        任务名称
 * @param schedule    cron 表达式（Spring 6 位格式）
 * @param instruction Agent 执行时的指令
 * @param status      任务状态：active / paused / completed
 * @param skillIds    创建时预加载的 Skill ID 列表（逗号分隔），可空
 * @param projectId   归属项目 ID，{@code null} 表示归属主账户
 * @param createdAt   创建时间（ISO 8601）
 * @param updatedAt   更新时间（ISO 8601）
 * @author zsg
 * @since 2026-04-23
 */
public record ScheduledTaskResponse(
        String id,
        String name,
        String schedule,
        String instruction,
        String status,
        @Nullable String skillIds,
        @Nullable String projectId,
        String createdAt,
        String updatedAt
) {
    /** 从领域对象构造响应 DTO。 */
    public static ScheduledTaskResponse from(CronTaskEntry e) {
        return new ScheduledTaskResponse(
                e.id(),
                e.name(),
                e.schedule(),
                e.instruction(),
                e.status(),
                e.skillIds(),
                e.projectId(),
                e.createdAt(),
                e.updatedAt()
        );
    }
}
