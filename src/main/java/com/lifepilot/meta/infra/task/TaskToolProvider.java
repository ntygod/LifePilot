package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.List;
import java.util.Map;

/**
 * 自主任务工具提供者。
 *
 * <p>集中管理统一的 {@code cron} 元能力工具，通过 action 参数路由到
 * create / list / update / remove 四类具体定时任务操作。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class TaskToolProvider {

    private static final List<String> TASK_TAGS = List.of("task", "automation");

    private final CronTaskRepository cronTaskRepository;
    private final CronScheduler cronScheduler;

    public TaskToolProvider(CronTaskRepository cronTaskRepository,
                            CronScheduler cronScheduler) {
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;
    }

    /**
     * 构建 Cron 工具列表（1 个）。
     *
     * @return Cron 工具列表
     */
    public List<BuiltinTool> buildCronTools() {
        var executor = new CronActionDispatchExecutor(cronTaskRepository, cronScheduler);
        return List.of(buildCronTool(executor));
    }

    /** 构建统一 Cron 工具。 */
    private BuiltinTool buildCronTool(CronActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("cron")
                .category(ToolCategory.ACTION)
                .name("定时任务")
                .description("定时任务管理")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("create", "list", "update", "remove"),
                                        "description", "操作类型：create=创建, list=查询, update=更新（未传字段保持不变）, remove=删除")),
                                Map.entry("taskId", Map.of(
                                        "type", "string",
                                        "description", "任务 ID；create 时可省略由系统生成，update/remove 必填")),
                                Map.entry("name", Map.of(
                                        "type", "string",
                                        "description", "任务名称；create 必填")),
                                Map.entry("schedule", Map.of(
                                        "type", "string",
                                        "description", "6 位 Cron 表达式（秒 分 时 日 月 周），如 0 30 9 * * MON-FRI；create 必填")),
                                Map.entry("instruction", Map.of(
                                        "type", "string",
                                        "description", "任务触发时 Agent 执行的 prompt 指令；create 必填")),
                                Map.entry("status", Map.of(
                                        "type", "string",
                                        "enum", List.of("active", "paused", "completed"),
                                        "description", "list 时作为筛选条件，update 时作为目标状态"))
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.CREATE_SCHEDULE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("taskIds", "taskId", "name")
                ))
                .tags(TASK_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}
