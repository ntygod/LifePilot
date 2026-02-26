package com.lifepilot.workflow.trigger;

import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.model.WorkflowTrigger;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 工作流触发器管理器 — 统一管理 CronTrigger 调度和 EventTrigger 监听。
 *
 * <p>核心职责：
 * <ul>
 *   <li>为包含 {@link WorkflowTrigger.CronTrigger} 的工作流注册定时调度任务</li>
 *   <li>为包含 {@link WorkflowTrigger.EventTrigger} 的工作流监听 Spring ApplicationEvent</li>
 *   <li>{@link WorkflowTrigger.ManualTrigger} 仅通过 {@code WorkflowEngine.execute()} 显式调用，无需注册</li>
 * </ul>
 *
 * <p>CronTrigger 触发时检查同一工作流是否有 RUNNING 实例，有则跳过并记录 INFO 日志。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowTriggerManager implements GenericApplicationListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerManager.class);

    private final WorkflowEngine engine;
    private final WorkflowRegistry registry;
    private final WorkflowRepository repository;
    private final TaskScheduler taskScheduler;

    /** 已注册的 Cron 调度任务，key 为 workflowId，用于取消调度。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> cronTasks = new ConcurrentHashMap<>();

    /** 已注册的事件触发器映射，key 为 eventType，value 为 workflowId 列表。 */
    private final ConcurrentHashMap<String, List<String>> eventBindings = new ConcurrentHashMap<>();

    public WorkflowTriggerManager(WorkflowEngine engine,
                                   WorkflowRegistry registry,
                                   WorkflowRepository repository,
                                   TaskScheduler taskScheduler) {
        this.engine = engine;
        this.registry = registry;
        this.repository = repository;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 注册所有已启用工作流的触发器。
     *
     * <p>遍历注册中心中所有已启用的工作流定义，为每个触发器注册对应的调度/监听。
     * 通常在 ApplicationReadyEvent 后调用。
     */
    public void registerAllTriggers() {
        List<WorkflowDefinition> enabled = registry.listEnabled();
        for (WorkflowDefinition def : enabled) {
            registerTriggers(def);
        }
        log.info("工作流触发器注册完成: 已启用工作流数={}, Cron任务数={}, 事件绑定数={}",
                enabled.size(), cronTasks.size(), eventBindings.size());
    }

    /**
     * 为单个工作流定义注册触发器。
     */
    public void registerTriggers(WorkflowDefinition definition) {
        for (WorkflowTrigger trigger : definition.triggers()) {
            switch (trigger) {
                case WorkflowTrigger.CronTrigger cron -> registerCron(definition.id(), cron);
                case WorkflowTrigger.EventTrigger event -> registerEvent(definition.id(), event);
                case WorkflowTrigger.ManualTrigger _ -> {
                    // ManualTrigger 无需注册，仅通过 WorkflowEngine.execute() 调用
                    log.debug("ManualTrigger 跳过注册: workflowId={}", definition.id());
                }
            }
        }
    }

    /**
     * 注销指定工作流的所有触发器。
     */
    public void unregisterTriggers(String workflowId) {
        // 取消 Cron 调度
        ScheduledFuture<?> future = cronTasks.remove(workflowId);
        if (future != null) {
            future.cancel(false);
            log.info("Cron 调度已取消: workflowId={}", workflowId);
        }

        // 移除事件绑定
        eventBindings.values().forEach(ids -> ids.remove(workflowId));
    }

    // ==================== CronTrigger ====================

    private void registerCron(String workflowId, WorkflowTrigger.CronTrigger cron) {
        try {
            var springCron = new org.springframework.scheduling.support.CronTrigger(cron.cron());
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> fireCron(workflowId), springCron);
            cronTasks.put(workflowId, future);
            log.info("Cron 调度已注册: workflowId={}, cron={}", workflowId, cron.cron());
        } catch (IllegalArgumentException e) {
            log.warn("Cron 表达式无效，跳过注册: workflowId={}, cron={}, 原因={}",
                    workflowId, cron.cron(), e.getMessage());
        }
    }

    /**
     * Cron 触发执行 — 检查是否有 RUNNING 实例，有则跳过。
     */
    private void fireCron(String workflowId) {
        try {
            // 检查是否有 RUNNING 实例
            boolean hasRunning = !repository.findInstancesByState(WorkflowState.RUNNING)
                    .stream()
                    .filter(inst -> workflowId.equals(inst.workflowId()))
                    .toList()
                    .isEmpty();

            if (hasRunning) {
                log.info("Cron 触发跳过（存在 RUNNING 实例）: workflowId={}", workflowId);
                return;
            }

            log.info("Cron 触发执行: workflowId={}", workflowId);
            engine.execute(workflowId, Map.of());
        } catch (Exception e) {
            log.error("Cron 触发执行失败: workflowId={}, 原因={}", workflowId, e.getMessage());
        }
    }

    // ==================== EventTrigger ====================

    private void registerEvent(String workflowId, WorkflowTrigger.EventTrigger event) {
        eventBindings.computeIfAbsent(event.eventType(),
                k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(workflowId);
        log.info("事件触发器已注册: workflowId={}, eventType={}", workflowId, event.eventType());
    }

    // ==================== GenericApplicationListener 实现 ====================

    @Override
    public boolean supportsEventType(@NonNull ResolvableType eventType) {
        // 监听所有 ApplicationEvent，在 onApplicationEvent 中按 eventType 名称过滤
        return true;
    }

    @Override
    public boolean supportsSourceType(@Nullable Class<?> sourceType) {
        return true;
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationEvent event) {
        String eventTypeName = event.getClass().getSimpleName();

        List<String> workflowIds = eventBindings.get(eventTypeName);
        if (workflowIds == null || workflowIds.isEmpty()) {
            return;
        }

        for (String workflowId : workflowIds) {
            try {
                log.info("事件触发执行: workflowId={}, eventType={}", workflowId, eventTypeName);
                // 将事件类名作为输入参数
                engine.execute(workflowId, Map.of("eventType", eventTypeName));
            } catch (Exception e) {
                log.error("事件触发执行失败: workflowId={}, eventType={}, 原因={}",
                        workflowId, eventTypeName, e.getMessage());
            }
        }
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}
