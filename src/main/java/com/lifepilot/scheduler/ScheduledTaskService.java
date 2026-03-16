package com.lifepilot.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.scheduler.model.ScheduledTask;
import com.lifepilot.scheduler.model.TaskAction;
import com.lifepilot.scheduler.model.TaskActionCodec;
import com.lifepilot.scheduler.model.TaskStatus;
import com.lifepilot.scheduler.model.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 定时任务业务服务层。
 *
 * <p>提供定时任务的创建、查询、取消、修改等业务操作，
 * 协调 {@link ScheduledTaskRepository} 和 {@link TaskScheduler} 完成持久化与调度注册。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScheduledTaskRepository repository;
    private final TaskScheduler taskScheduler;

    public ScheduledTaskService(ScheduledTaskRepository repository, TaskScheduler taskScheduler) {
        this.repository = repository;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 创建定时任务。
     *
     * <p>校验触发时间/cron 表达式有效性，生成 UUID，持久化到数据库，注册调度。</p>
     *
     * @param name        任务名称
     * @param triggerType 触发类型（ONCE / CRON）
     * @param triggerAt   一次性任务触发时间（ISO 8601），CRON 类型时为 null
     * @param cronExpr    周期性任务 cron 表达式，ONCE 类型时为 null
     * @param action      任务动作
     * @param metadata    扩展元数据（如关联 scheduleId）
     * @return 任务 ID
     * @throws IllegalArgumentException 触发时间无效或 cron 表达式格式错误
     */
    public String create(String name, TriggerType triggerType,
                         @Nullable String triggerAt, @Nullable String cronExpr,
                         TaskAction action, @Nullable Map<String, String> metadata) {
        // 校验一次性任务触发时间
        if (triggerType == TriggerType.ONCE) {
            if (triggerAt == null || Instant.parse(triggerAt).isBefore(Instant.now())) {
                throw new IllegalArgumentException("触发时间不能早于当前时间");
            }
        }

        // 校验周期性任务 cron 表达式
        if (triggerType == TriggerType.CRON) {
            if (cronExpr == null || !CronExpression.isValidExpression(cronExpr)) {
                throw new IllegalArgumentException("cron 表达式格式无效");
            }
        }

        String id = UUID.randomUUID().toString();
        String actionJson = TaskActionCodec.toJson(action);
        String metadataJson = serializeMetadata(metadata);
        String now = Instant.now().toString();

        // 计算 nextTriggerAt
        String nextTriggerAt = calculateNextTriggerAt(triggerType, triggerAt, cronExpr);

        ScheduledTask task = new ScheduledTask(
                id, name, triggerType, triggerAt, cronExpr,
                actionJson, TaskStatus.PENDING, null,
                null, nextTriggerAt,
                metadataJson, now, now
        );

        repository.save(task);

        // 注册调度
        if (triggerType == TriggerType.ONCE) {
            taskScheduler.scheduleOnce(task);
        } else {
            taskScheduler.scheduleCron(task);
        }

        log.info("定时任务创建成功: id={}, name={}, triggerType={}", id, name, triggerType);
        return id;
    }

    /**
     * 查询所有未取消的定时任务列表，按 nextTriggerAt 升序排列（null 排最后）。
     *
     * @return 不可变的活跃任务列表
     */
    public List<ScheduledTask> listActive() {
        return List.copyOf(repository.findAllActive());
    }

    /**
     * 按 ID 查询定时任务。
     *
     * @param taskId 任务 ID
     * @return 定时任务 Optional
     */
    public Optional<ScheduledTask> findById(String taskId) {
        return repository.findById(taskId);
    }

    /**
     * 取消定时任务。
     *
     * <p>校验任务存在且状态可取消，更新状态为 CANCELLED，从调度器移除。</p>
     *
     * @param taskId 任务 ID
     * @throws IllegalArgumentException 任务不存在或状态不可取消
     */
    public void cancel(String taskId) {
        ScheduledTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));

        if (task.status() == TaskStatus.COMPLETED || task.status() == TaskStatus.CANCELLED) {
            throw new IllegalArgumentException("任务已完成或已取消，无法再次取消");
        }

        repository.updateStatus(taskId, TaskStatus.CANCELLED, null);
        taskScheduler.cancel(taskId);
        log.info("定时任务已取消: id={}, name={}", taskId, task.name());
    }

    /**
     * 修改定时任务。
     *
     * <p>校验任务存在且状态可修改，更新字段，取消旧调度并重新注册。
     * 传入 null 的参数保持原值不变。</p>
     *
     * @param taskId    任务 ID
     * @param triggerAt 新的触发时间（null 表示不修改）
     * @param cronExpr  新的 cron 表达式（null 表示不修改）
     * @param action    新的任务动作（null 表示不修改）
     * @param name      新的任务名称（null 表示不修改）
     * @throws IllegalArgumentException 任务不存在、状态不可修改、或参数无效
     */
    public void update(String taskId, @Nullable String triggerAt,
                       @Nullable String cronExpr, @Nullable TaskAction action,
                       @Nullable String name) {
        ScheduledTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));

        if (task.status() == TaskStatus.COMPLETED || task.status() == TaskStatus.CANCELLED) {
            throw new IllegalArgumentException("任务已完成或已取消，无法修改");
        }

        // 确定最终值（null 参数保持原值）
        String finalName = name != null ? name : task.name();
        String finalTriggerAt = triggerAt != null ? triggerAt : task.triggerAt();
        String finalCronExpr = cronExpr != null ? cronExpr : task.cronExpr();
        String finalActionJson = action != null ? TaskActionCodec.toJson(action) : task.actionJson();

        // 校验新的触发时间
        if (triggerAt != null && task.triggerType() == TriggerType.ONCE) {
            if (Instant.parse(triggerAt).isBefore(Instant.now())) {
                throw new IllegalArgumentException("触发时间不能早于当前时间");
            }
        }

        // 校验新的 cron 表达式
        if (cronExpr != null && task.triggerType() == TriggerType.CRON) {
            if (!CronExpression.isValidExpression(cronExpr)) {
                throw new IllegalArgumentException("cron 表达式格式无效");
            }
        }

        // 重新计算 nextTriggerAt
        String nextTriggerAt = calculateNextTriggerAt(task.triggerType(), finalTriggerAt, finalCronExpr);

        ScheduledTask updated = new ScheduledTask(
                task.id(), finalName, task.triggerType(), finalTriggerAt, finalCronExpr,
                finalActionJson, task.status(), task.errorMessage(),
                task.lastTriggeredAt(), nextTriggerAt,
                task.metadataJson(), task.createdAt(), Instant.now().toString()
        );

        repository.update(updated);

        // 取消旧调度并重新注册
        taskScheduler.cancel(taskId);
        if (task.triggerType() == TriggerType.ONCE) {
            taskScheduler.scheduleOnce(updated);
        } else {
            taskScheduler.scheduleCron(updated);
        }

        log.info("定时任务已修改: id={}, name={}", taskId, finalName);
    }

    /**
     * 按 metadata 中的 scheduleId 查找关联的定时任务。
     *
     * @param scheduleId 日程 ID
     * @return 关联的定时任务 Optional
     */
    public Optional<ScheduledTask> findByScheduleId(String scheduleId) {
        return repository.findByMetadataScheduleId(scheduleId);
    }

    // ---- 内部方法 ----

    /**
     * 计算下次触发时间。
     *
     * @param triggerType 触发类型
     * @param triggerAt   一次性任务触发时间
     * @param cronExpr    周期性任务 cron 表达式
     * @return 下次触发时间（ISO 8601），无法计算时返回 null
     */
    @Nullable
    private String calculateNextTriggerAt(TriggerType triggerType,
                                          @Nullable String triggerAt,
                                          @Nullable String cronExpr) {
        if (triggerType == TriggerType.ONCE) {
            return triggerAt;
        }
        if (triggerType == TriggerType.CRON && cronExpr != null) {
            CronExpression cron = CronExpression.parse(cronExpr);
            LocalDateTime next = cron.next(LocalDateTime.now());
            if (next != null) {
                return next.atZone(ZoneId.systemDefault()).toInstant().toString();
            }
        }
        return null;
    }

    /**
     * 将 metadata Map 序列化为 JSON 字符串。
     *
     * @param metadata 元数据 Map，可为 null
     * @return JSON 字符串，metadata 为 null 或空时返回 null
     */
    @Nullable
    private String serializeMetadata(@Nullable Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("metadata 序列化失败，忽略: {}", e.getMessage());
            return null;
        }
    }
}
