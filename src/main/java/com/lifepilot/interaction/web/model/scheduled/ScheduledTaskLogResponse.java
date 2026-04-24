package com.lifepilot.interaction.web.model.scheduled;

import com.lifepilot.agent.task.CronTaskLog;
import org.springframework.lang.Nullable;

/**
 * 定时任务执行日志 API 响应 DTO。
 *
 * <p>字段直接映射 {@link CronTaskLog}，executedAt 保持 String（ISO 8601），
 * 前端按需解析成本地时间展示。summary 可能为 null（执行异常前未落 summary）。</p>
 *
 * @param id         日志 ID（UUID）
 * @param executedAt 执行时间（ISO 8601）
 * @param status     执行状态：success / failed / timeout
 * @param durationMs 执行耗时（毫秒）
 * @param tokensUsed Token 消耗量
 * @param summary    Agent 回复摘要（前 500 字符，可空）
 * @author zsg
 * @since 2026-04-24
 */
public record ScheduledTaskLogResponse(
        String id,
        String executedAt,
        String status,
        long durationMs,
        int tokensUsed,
        @Nullable String summary
) {
    /** 从领域对象构造响应 DTO。 */
    public static ScheduledTaskLogResponse from(CronTaskLog log) {
        return new ScheduledTaskLogResponse(
                log.id(),
                log.executedAt(),
                log.status(),
                log.durationMs(),
                log.tokensUsed(),
                log.summary()
        );
    }
}
