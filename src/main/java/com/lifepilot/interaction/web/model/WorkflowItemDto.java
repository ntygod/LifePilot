package com.lifepilot.interaction.web.model;

import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowTrigger;

import java.util.List;

/**
 * 工作流列表项 DTO，对齐前端 WorkflowItem 类型。
 *
 * <p>将后端 {@link WorkflowDefinition} 中的 {@code triggers} sealed interface 列表
 * 转换为前端期望的 {@code triggerTypes} 字符串列表。
 *
 * @param id           工作流 ID
 * @param name         工作流名称
 * @param description  工作流描述
 * @param enabled      是否启用
 * @param triggerTypes 触发器类型名称列表（如 "manual"、"cron"、"event"）
 * @param version      版本号
 * @author zsg
 * @since 2026-03-11
 */
public record WorkflowItemDto(
        String id,
        String name,
        String description,
        boolean enabled,
        List<String> triggerTypes,
        String version
) {

    /**
     * 从 WorkflowDefinition 构建列表项 DTO。
     */
    public static WorkflowItemDto from(WorkflowDefinition def) {
        List<String> types = def.triggers().stream()
                .map(WorkflowItemDto::triggerTypeName)
                .toList();
        return new WorkflowItemDto(
                def.id(), def.name(), def.description(),
                def.enabled(), types, def.version()
        );
    }

    private static String triggerTypeName(WorkflowTrigger trigger) {
        return switch (trigger) {
            case WorkflowTrigger.CronTrigger _ -> "cron";
            case WorkflowTrigger.EventTrigger _ -> "event";
            case WorkflowTrigger.ManualTrigger _ -> "manual";
            case WorkflowTrigger.WebhookTrigger _ -> "webhook";
        };
    }
}
