package com.lifepilot.interaction.web.model.scheduled;

import com.lifepilot.agent.task.CronTaskEntry;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 定时任务 API 响应 DTO。
 *
 * <p>字段直接映射 {@link CronTaskEntry}，无枚举转换，createdAt / updatedAt 保持
 * String 类型（ISO 8601，与持久层一致，前端按需解析）。</p>
 *
 * <p>{@code nextExecutionAt} 由 Controller 层基于 cron 表达式动态计算，
 * 仅在 active 状态下非空；paused / completed / 非法表达式均为 null。</p>
 *
 * @param id              任务 ID（UUID）
 * @param name            任务名称
 * @param schedule        cron 表达式（Spring 6 位格式）
 * @param instruction     Agent 执行时的指令
 * @param status          任务状态：active / paused / completed
 * @param skillIds        创建时预加载的 Skill ID 列表（逗号分隔），可空
 * @param projectId       归属项目 ID，{@code null} 表示归属主账户
 * @param createdAt       创建时间（ISO 8601）
 * @param updatedAt       更新时间（ISO 8601）
 * @param nextExecutionAt 下次预计执行时间（ISO 8601），仅 active 非空
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
        String updatedAt,
        @Nullable String nextExecutionAt
) {
    /**
     * 从领域对象构造响应 DTO（不含 nextExecutionAt）。
     *
     * <p>仅用于 update / delete 等单条操作的响应——前端可自行基于
     * {@link #fromWithNext(CronTaskEntry)} 拿到"下次执行时间"。</p>
     */
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
                e.updatedAt(),
                null
        );
    }

    /**
     * 从领域对象构造响应 DTO，并计算"下次执行时间"。
     *
     * <p>只对 {@code status == "active"} 的任务计算；paused / completed
     * 都返回 null。cron 表达式非法时也返回 null 不抛异常——避免
     * 单条坏数据拖垮整个列表。</p>
     */
    public static ScheduledTaskResponse fromWithNext(CronTaskEntry e) {
        String nextIso = null;
        if ("active".equals(e.status())) {
            try {
                LocalDateTime next = CronExpression.parse(e.schedule()).next(LocalDateTime.now());
                if (next != null) {
                    nextIso = next.atZone(ZoneId.systemDefault()).toInstant().toString();
                }
            } catch (Exception ignored) {
                // 非法 cron 表达式：返回 null，由前端兜底显示"schedule 无效"
            }
        }
        return new ScheduledTaskResponse(
                e.id(),
                e.name(),
                e.schedule(),
                e.instruction(),
                e.status(),
                e.skillIds(),
                e.projectId(),
                e.createdAt(),
                e.updatedAt(),
                nextIso
        );
    }
}
