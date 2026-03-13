package com.lifepilot.workflow.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.springframework.lang.Nullable;

/**
 * 工作流触发器 sealed interface。
 *
 * <p>定义工作流的四种触发方式：
 * <ul>
 *   <li>{@link CronTrigger} — 按 Cron 表达式定时触发</li>
 *   <li>{@link EventTrigger} — 监听 Spring ApplicationEvent 事件触发</li>
 *   <li>{@link ManualTrigger} — 仅通过 WorkflowCommandService.start() 手动触发</li>
 *   <li>{@link WebhookTrigger} — 通过 HTTP Webhook 回调触发</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WorkflowTrigger.CronTrigger.class, name = "cron"),
        @JsonSubTypes.Type(value = WorkflowTrigger.EventTrigger.class, name = "event"),
        @JsonSubTypes.Type(value = WorkflowTrigger.ManualTrigger.class, name = "manual"),
        @JsonSubTypes.Type(value = WorkflowTrigger.WebhookTrigger.class, name = "webhook")
})
public sealed interface WorkflowTrigger permits
        WorkflowTrigger.CronTrigger,
        WorkflowTrigger.EventTrigger,
        WorkflowTrigger.ManualTrigger,
        WorkflowTrigger.WebhookTrigger {

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

    /**
     * Webhook 触发器，通过 HTTP 回调触发工作流执行。
     *
     * <p>外部系统（如 GitHub、飞书机器人等）可通过 POST /api/workflows/{id}/webhook 触发。
     * 配置 {@code secret} 时启用 HMAC-SHA256 签名验证。
     *
     * @param secret 可选的签名密钥，用于 HMAC-SHA256 验证
     * @author zsg
     * @since 2026-03-13
     */
    record WebhookTrigger(@Nullable String secret) implements WorkflowTrigger {}
}
