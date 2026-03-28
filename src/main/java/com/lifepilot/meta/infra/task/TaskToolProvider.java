package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自主任务工具提供者 — 构建 Cron 定时任务的 {@link BuiltinTool} 列表。
 *
 * <p>从 {@link com.lifepilot.meta.infra.InfraToolProvider} 委托调用，
 * 集中管理 4 个任务工具（cron: create/list/update/remove）。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class TaskToolProvider {

    private static final Logger log = LoggerFactory.getLogger(TaskToolProvider.class);
    private static final List<String> TASK_TAGS = List.of("task", "automation");

    private final CronTaskRepository cronTaskRepository;
    private final CronScheduler cronScheduler;

    public TaskToolProvider(CronTaskRepository cronTaskRepository,
                            CronScheduler cronScheduler) {
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;
    }

    // ─────────────────────────────────────────────
    //  Cron 定时任务工具（4 个）
    // ─────────────────────────────────────────────

    /**
     * 构建所有 Cron 定时任务工具。
     *
     * @return Cron 工具列表（create / list / update / remove）
     */
    public List<BuiltinTool> buildCronTools() {
        return List.of(
                buildCronCreateTool(),
                buildCronListTool(),
                buildCronUpdateTool(),
                buildCronRemoveTool()
        );
    }

    /** 构建创建定时任务工具。 */
    private BuiltinTool buildCronCreateTool() {
        return BuiltinTool.builder()
                .id("cron.create")
                .category(ToolCategory.ACTION)
                .name("创建定时任务")
                .description("创建 Cron 定时任务，写入数据库后立即注册精确定时器。schedule 使用 Spring 6 位 Cron 格式（秒 分 时 日 月 周）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("name", "schedule", "instruction"),
                        "properties", Map.of(
                                "taskId", Map.of("type", "string",
                                        "description", "任务 ID；通常由系统预先生成"),
                                "name", Map.of("type", "string",
                                        "description", "任务名称"),
                                "schedule", Map.of("type", "string",
                                        "description", "Cron 表达式（6 位：秒 分 时 日 月 周），如 0 0 8 * * * 表示每天 8 点"),
                                "instruction", Map.of("type", "string",
                                        "description", "Agent 执行时的 prompt 指令")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId", "name")
                ))
                .tags(TASK_TAGS)
                .executor(input -> {
                    try {
                        String taskId = input.getOptionalParam("taskId", String.class)
                                .orElse(UUID.randomUUID().toString());
                        String name = input.getParam("name", String.class);
                        String schedule = input.getParam("schedule", String.class);
                        String instruction = input.getParam("instruction", String.class);

                        org.springframework.scheduling.support.CronExpression.parse(schedule);

                        String now = Instant.now().toString();
                        var entry = new CronTaskEntry(
                                taskId, name, schedule, instruction,
                                "active", now, now
                        );
                        cronTaskRepository.save(entry);
                        cronScheduler.schedule(entry);

                        log.info("定时任务创建成功: id={}, name={}, schedule={}", entry.id(), name, schedule);
                        return com.lifepilot.tool.model.ToolResult.success(Map.of(
                                "id", entry.id(),
                                "name", name,
                                "schedule", schedule,
                                "status", "active"
                        ));
                    } catch (IllegalArgumentException e) {
                        return com.lifepilot.tool.model.ToolResult.error("Cron 表达式无效: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("创建定时任务失败: {}", e.getMessage(), e);
                        return com.lifepilot.tool.model.ToolResult.error("创建定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询定时任务列表工具。 */
    private BuiltinTool buildCronListTool() {
        return BuiltinTool.builder()
                .id("cron.list")
                .category(ToolCategory.PERCEPTION)
                .name("查询定时任务列表")
                .description("查询所有定时任务，可按状态过滤（active / paused / completed）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "status", Map.of("type", "string",
                                        "description", "状态过滤: active / paused / completed，不传则返回全部")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(TASK_TAGS)
                .executor(input -> {
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
                        return com.lifepilot.tool.model.ToolResult.success(Map.of("tasks", items));
                    } catch (Exception e) {
                        log.error("查询定时任务失败: {}", e.getMessage(), e);
                        return com.lifepilot.tool.model.ToolResult.error("查询定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建更新定时任务工具。 */
    private BuiltinTool buildCronUpdateTool() {
        return BuiltinTool.builder()
                .id("cron.update")
                .category(ToolCategory.ACTION)
                .name("更新定时任务")
                .description("更新定时任务的名称、Cron 表达式、指令或状态。修改 status 或 schedule 时自动重新注册定时器")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("taskId"),
                        "properties", Map.of(
                                "taskId", Map.of("type", "string",
                                        "description", "任务 ID"),
                                "name", Map.of("type", "string",
                                        "description", "新的任务名称"),
                                "schedule", Map.of("type", "string",
                                        "description", "新的 Cron 表达式"),
                                "instruction", Map.of("type", "string",
                                        "description", "新的执行指令"),
                                "status", Map.of("type", "string",
                                        "description", "新的状态: active / paused / completed")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId")
                ))
                .tags(TASK_TAGS)
                .executor(input -> {
                    try {
                        String taskId = input.getParam("taskId", String.class);
                        var existing = cronTaskRepository.findById(taskId);
                        if (existing.isEmpty()) {
                            return com.lifepilot.tool.model.ToolResult.error("任务不存在: id=" + taskId);
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
                        return com.lifepilot.tool.model.ToolResult.success(Map.of(
                                "id", taskId,
                                "name", name,
                                "schedule", schedule,
                                "status", status
                        ));
                    } catch (IllegalArgumentException e) {
                        return com.lifepilot.tool.model.ToolResult.error("Cron 表达式无效: " + e.getMessage());
                    } catch (Exception e) {
                        log.error("更新定时任务失败: {}", e.getMessage(), e);
                        return com.lifepilot.tool.model.ToolResult.error("更新定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建删除定时任务工具。 */
    private BuiltinTool buildCronRemoveTool() {
        return BuiltinTool.builder()
                .id("cron.remove")
                .category(ToolCategory.ACTION)
                .name("删除定时任务")
                .description("删除定时任务，取消定时器并删除数据库记录（级联删除执行日志）")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("taskId"),
                        "properties", Map.of(
                                "taskId", Map.of("type", "string",
                                        "description", "任务 ID")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId")
                ))
                .tags(TASK_TAGS)
                .executor(input -> {
                    try {
                        String taskId = input.getParam("taskId", String.class);
                        cronScheduler.cancel(taskId);
                        cronTaskRepository.deleteById(taskId);

                        log.info("定时任务删除成功: id={}", taskId);
                        return com.lifepilot.tool.model.ToolResult.success(Map.of("deleted", true, "id", taskId));
                    } catch (Exception e) {
                        log.error("删除定时任务失败: {}", e.getMessage(), e);
                        return com.lifepilot.tool.model.ToolResult.error("删除定时任务失败: " + e.getMessage());
                    }
                })
                .build();
    }
}
