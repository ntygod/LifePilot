package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.springframework.lang.Nullable;

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

    private static final List<String> TASK_TAGS = List.of("定时", "任务", "调度", "自动化", "周期", "提醒",
            "cron", "schedule", "task", "timer");

    private final CronTaskRepository cronTaskRepository;
    private final CronScheduler cronScheduler;
    @Nullable private final ChatSessionRepository chatSessionRepository;

    public TaskToolProvider(CronTaskRepository cronTaskRepository,
                            CronScheduler cronScheduler,
                            @Nullable ChatSessionRepository chatSessionRepository) {
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;
        this.chatSessionRepository = chatSessionRepository;
    }

    /**
     * 构建 Cron 工具列表（1 个）。
     *
     * @return Cron 工具列表
     */
    public List<BuiltinTool> buildCronTools() {
        var executor = new CronActionDispatchExecutor(cronTaskRepository, cronScheduler, chatSessionRepository);
        return List.of(buildCronTool(executor));
    }

    /** 构建统一 Cron 工具。 */
    private BuiltinTool buildCronTool(CronActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("cron")
                .category(ToolCategory.ACTION)
                .name("定时任务")
                .description("""
                        定时任务调度。action: create(创建) list(查询，支持status过滤) update(更新，未传字段保持不变) delete(删除，同时清执行日志)。
                        schedule 为 Spring 6 位 cron（秒 分 时 日 月 周），不是 Linux 5 位。* = 每个 */n = 每n个 a-b = 范围 a,b = 列举 ? = 日/周二选一。周用大写 MON-SUN。
                        示例: "0 0 8 * * *"=每天8点 "0 */30 * * * *"=每30分钟 "0 0 18 * * MON-FRI"=工作日下午6点。
                        create 时 taskId 可省略由系统生成。instruction 是触发后让 Agent 做什么的 prompt。
                        taskId 找不到时先 list 拿真实 id，不要凭记忆传。""")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("create", "list", "update", "delete"),
                                        "description", "操作类型：create=创建, list=查询, update=更新（未传字段保持不变）, delete=删除")),
                                Map.entry("taskId", Map.of(
                                        "type", "string",
                                        "description", "任务 ID；create 时可省略由系统生成，update/delete 必填")),
                                Map.entry("name", Map.of(
                                        "type", "string",
                                        "description", "任务名称；create 必填")),
                                Map.entry("schedule", Map.of(
                                        "type", "string",
                                        "description", "6 位 Cron 表达式（秒 分 时 日 月 周），如 0 30 9 * * MON-FRI；create 必填")),
                                Map.entry("instruction", Map.of(
                                        "type", "string",
                                        "description", "任务触发时 Agent 执行的 prompt 指令；create 必填")),
                                Map.entry("skillIds", Map.of(
                                        "type", "string",
                                        "description", "当前已加载的 Skill ID（逗号分隔），任务执行时自动预加载")),
                                Map.entry("status", Map.of(
                                        "type", "string",
                                        "enum", List.of("active", "paused", "completed"),
                                        "description", "list 时作为筛选条件，update 时作为目标状态"))
                        ),
                        "dependentRequired", Map.of(
                                "create", List.of("name", "schedule", "instruction"),
                                "update", List.of("taskId"),
                                "delete", List.of("taskId")
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
