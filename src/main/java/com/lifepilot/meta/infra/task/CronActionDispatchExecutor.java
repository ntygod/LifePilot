package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Cron 工具 action 路由执行器。
 *
 * <p>统一承接 create / list / update / remove 四类定时任务操作。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class CronActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(CronActionDispatchExecutor.class);

    private final CronTaskRepository cronTaskRepository;
    private final CronScheduler cronScheduler;

    public CronActionDispatchExecutor(CronTaskRepository cronTaskRepository,
                                      CronScheduler cronScheduler) {
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;

        register("create",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId", "name")
                ),
                input -> {
                    try {
                        String taskId = input.getOptionalParam("taskId", String.class)
                                .orElse(UUID.randomUUID().toString());
                        String name = input.getParam("name", String.class);
                        String schedule = input.getParam("schedule", String.class);
                        String instruction = input.getParam("instruction", String.class);

                        org.springframework.scheduling.support.CronExpression.parse(schedule);

                        String now = Instant.now().toString();
                        var entry = new CronTaskEntry(taskId, name, schedule, instruction, "active", now, now);
                        cronTaskRepository.save(entry);
                        cronScheduler.schedule(entry);

                        log.info("定时任务创建成功: id={}, name={}, schedule={}", entry.id(), name, schedule);
                        return ToolResult.success(Map.of(
                                "id", entry.id(),
                                "name", name,
                                "schedule", schedule,
                                "status", "active"
                        ));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("Cron 表达式无效: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("创建定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("创建定时任务失败: " + e.getMessage());
                    }
                });

        register("list",
                RiskLevel.LOW,
                ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE),
                input -> {
                    try {
                        String status = input.getOptionalParam("status", String.class).orElse(null);
                        var tasks = status != null
                                ? cronTaskRepository.findByStatus(status)
                                : cronTaskRepository.findAll();

                        var items = tasks.stream()
                                .map(t -> Map.<String, Object>of(
                                        "id", t.id(),
                                        "name", t.name(),
                                        "schedule", t.schedule(),
                                        "instruction", t.instruction(),
                                        "status", t.status(),
                                        "createdAt", t.createdAt()
                                ))
                                .toList();
                        return ToolResult.success(Map.of("tasks", items));
                    } catch (Exception e) {
                        log.error("查询定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询定时任务失败: " + e.getMessage());
                    }
                });

        register("update",
                RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId")
                ),
                input -> {
                    try {
                        String taskId = input.getParam("taskId", String.class);
                        var existing = cronTaskRepository.findById(taskId);
                        if (existing.isEmpty()) {
                            return ToolResult.error("任务不存在: id=" + taskId);
                        }
                        var old = existing.get();

                        String name = input.getOptionalParam("name", String.class).orElse(old.name());
                        String schedule = input.getOptionalParam("schedule", String.class).orElse(old.schedule());
                        String instruction = input.getOptionalParam("instruction", String.class).orElse(old.instruction());
                        String status = input.getOptionalParam("status", String.class).orElse(old.status());

                        if (!schedule.equals(old.schedule())) {
                            org.springframework.scheduling.support.CronExpression.parse(schedule);
                        }

                        var updated = new CronTaskEntry(
                                taskId, name, schedule, instruction, status,
                                old.createdAt(), Instant.now().toString()
                        );
                        cronTaskRepository.update(updated);

                        boolean scheduleChanged = !schedule.equals(old.schedule());
                        boolean statusChanged = !status.equals(old.status());
                        if (scheduleChanged || statusChanged) {
                            cronScheduler.cancel(taskId);
                            if ("active".equals(status)) {
                                cronScheduler.schedule(updated);
                            }
                        }

                        log.info("定时任务更新成功: id={}, name={}", taskId, name);
                        return ToolResult.success(Map.of(
                                "id", taskId,
                                "name", name,
                                "schedule", schedule,
                                "status", status
                        ));
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("Cron 表达式无效: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("更新定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("更新定时任务失败: " + e.getMessage());
                    }
                });

        register("remove",
                RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId")
                ),
                input -> {
                    try {
                        String taskId = input.getParam("taskId", String.class);
                        cronScheduler.cancel(taskId);
                        cronTaskRepository.deleteById(taskId);

                        log.info("定时任务删除成功: id={}", taskId);
                        return ToolResult.success(Map.of("deleted", true, "id", taskId));
                    } catch (Exception e) {
                        log.error("删除定时任务失败: {}", e.getMessage(), e);
                        return ToolResult.error("删除定时任务失败: " + e.getMessage());
                    }
                });
    }
}
