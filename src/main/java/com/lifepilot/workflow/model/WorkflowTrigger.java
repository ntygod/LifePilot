package com.lifepilot.workflow.model;

/**
 * 工作流触发器 sealed interface。
 *
 * <p>定义工作流的三种触发方式：
 * <ul>
 *   <li>{@link CronTrigger} — 按 Cron 表达式定时触发</li>
 *   <li>{@link EventTrigger} — 监听 Spring ApplicationEvent 事件触发</li>
 *   <li>{@link ManualTrigger} — 仅通过 WorkflowEngine.execute() 手动触发</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public sealed interface WorkflowTrigger permits
        WorkflowTrigger.CronTrigger,
        WorkflowTrigger.EventTrigger,
        WorkflowTrigger.ManualTrigger {

    /**
     * Cron 定时触发器，按 Cron 表达式周期性执行工作流。
     *
     * @param cron Cron 表达式（如 {@code "0 21 * * *"}）
     */
    record CronTrigger(String cron) implements WorkflowTrigger {}

    /**
     * 事件触发器，监听指定的 Spring ApplicationEvent 类型。
     *
     * @param eventType 事件类型名称
     */
    record EventTrigger(String eventType) implements WorkflowTrigger {}

    /**
     * 手动触发器，仅通过 {@code WorkflowEngine.execute()} 显式调用时执行。
     */
    record ManualTrigger() implements WorkflowTrigger {}
}
