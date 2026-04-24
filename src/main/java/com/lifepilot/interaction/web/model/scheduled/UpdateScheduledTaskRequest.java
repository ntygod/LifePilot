package com.lifepilot.interaction.web.model.scheduled;

/**
 * 定时任务更新请求。
 *
 * <p>所有字段均可空——{@code null} 表示保留原值。前端若只想暂停任务，
 * 传 {@code {"status": "paused"}} 即可，其他字段保持不变。</p>
 *
 * <p>项目归属（project_id）不在更新字段中——归属不可迁移。</p>
 *
 * @param name        新任务名称
 * @param schedule    新 cron 表达式
 * @param instruction 新执行指令
 * @param status      新状态（active / paused / completed）
 * @author zsg
 * @since 2026-04-23
 */
public record UpdateScheduledTaskRequest(
        String name,
        String schedule,
        String instruction,
        String status
) {
}
