package com.lifepilot.meta.infra.task;

import com.lifepilot.agent.task.CronScheduler;
import com.lifepilot.agent.task.CronTaskEntry;
import com.lifepilot.agent.task.CronTaskRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cron 工具 action 路由执行器。
 *
 * <p>统一承接 create / list / update / remove 四类定时任务操作。</p>
 *
 * <p>create 路径会通过 {@link ChatSessionRepository} 反查当前会话的 projectId，
 * 填入新建的 {@link CronTaskEntry} —— 使得在隔离项目对话中创建的定时任务
 * 自动归属该项目（Plan 2 Task A6）。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class CronActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(CronActionDispatchExecutor.class);

    private final CronTaskRepository cronTaskRepository;
    private final CronScheduler cronScheduler;
    @Nullable private final ChatSessionRepository chatSessionRepository;

    /** 兼容旧调用点的构造器（无 ChatSessionRepository，projectId 始终为 null）。 */
    public CronActionDispatchExecutor(CronTaskRepository cronTaskRepository,
                                      CronScheduler cronScheduler) {
        this(cronTaskRepository, cronScheduler, null);
    }

    public CronActionDispatchExecutor(CronTaskRepository cronTaskRepository,
                                      CronScheduler cronScheduler,
                                      @Nullable ChatSessionRepository chatSessionRepository) {
        this.cronTaskRepository = cronTaskRepository;
        this.cronScheduler = cronScheduler;
        this.chatSessionRepository = chatSessionRepository;

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
                        String skillIds = input.getOptionalParam("skillIds", String.class)
                                .map(CronActionDispatchExecutor::stripSelfSkill)
                                .orElse(null);

                        CronExpression.parse(schedule);

                        String now = Instant.now().toString();
                        String projectId = resolveProjectIdOrNull(input);
                        var entry = new CronTaskEntry(taskId, name, schedule, instruction, "active", now, now, skillIds, projectId);
                        cronTaskRepository.save(entry);
                        cronScheduler.schedule(entry);

                        log.info("定时任务创建成功: id={}, name={}, schedule={}, projectId={}",
                                entry.id(), name, schedule, projectId);
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
                            return ToolResult.error("任务不存在: id=" + taskId + "，请用 list 查看可用任务");
                        }
                        var old = existing.get();

                        String name = input.getOptionalParam("name", String.class).orElse(old.name());
                        String schedule = input.getOptionalParam("schedule", String.class).orElse(old.schedule());
                        String instruction = input.getOptionalParam("instruction", String.class).orElse(old.instruction());
                        String status = input.getOptionalParam("status", String.class).orElse(old.status());

                        boolean scheduleChanged = !schedule.equals(old.schedule());
                        if (scheduleChanged) {
                            CronExpression.parse(schedule);
                        }

                        var updated = new CronTaskEntry(
                                taskId, name, schedule, instruction, status,
                                old.createdAt(), Instant.now().toString()
                        );
                        cronTaskRepository.update(updated);

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

        register("delete",
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

    /**
     * 反查当前 session 的 projectId。
     *
     * <p>ChatSessionRepository 缺失、sessionId 为空 / 未提供、session 不存在或查询异常时
     * 一律回退 {@code null}，表示归属主账户。这保证 Task A6 的功能降级时不破坏 cron 创建
     * 路径本身（例如未装载 interaction 模块的独立部署场景）。</p>
     *
     * @param input 工具输入（从 context 读取 sessionId）
     * @return projectId；无法反查时为 {@code null}
     */
    private @Nullable String resolveProjectIdOrNull(ToolInput input) {
        if (chatSessionRepository == null) {
            return null;
        }
        String sessionId = input.getContextValue("sessionId", String.class).orElse(null);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return chatSessionRepository.findById(sessionId)
                    .map(ChatSession::projectId)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("反查 session 项目归属失败，回退主账户: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 过滤掉 cron-scheduler 自身的 Skill ID —— 执行定时任务时不需要定时任务管理指南。
     *
     * @param raw 逗号分隔的 skillId 列表
     * @return 过滤后的列表，全部过滤完则返回 null
     */
    static @Nullable String stripSelfSkill(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String result = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(id -> !id.equals("cron-scheduler"))
                .collect(Collectors.joining(","));
        return result.isBlank() ? null : result;
    }
}
