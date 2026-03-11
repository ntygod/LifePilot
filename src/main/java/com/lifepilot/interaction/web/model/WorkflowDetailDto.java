package com.lifepilot.interaction.web.model;

import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowInputParam;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.model.WorkflowTrigger;

import java.util.List;
import java.util.Map;

/**
 * 工作流详情 DTO，对齐前端 WorkflowDetail 类型。
 *
 * <p>在 {@link WorkflowItemDto} 基础上增加 triggers、inputs、steps、metadata 字段，
 * 同时保留 triggerTypes 字符串列表供前端列表/概览使用。
 *
 * @param id           工作流 ID
 * @param name         工作流名称
 * @param description  工作流描述
 * @param enabled      是否启用
 * @param triggerTypes 触发器类型名称列表
 * @param version      版本号
 * @param triggers     完整触发器定义列表
 * @param inputs       输入参数定义
 * @param steps        步骤定义列表
 * @param metadata     自定义元数据
 * @author zsg
 * @since 2026-03-11
 */
public record WorkflowDetailDto(
        String id,
        String name,
        String description,
        boolean enabled,
        List<String> triggerTypes,
        String version,
        List<WorkflowTrigger> triggers,
        Map<String, WorkflowInputParam> inputs,
        List<WorkflowStep> steps,
        Map<String, String> metadata
) {

    /**
     * 从 WorkflowDefinition 构建详情 DTO。
     */
    public static WorkflowDetailDto from(WorkflowDefinition def) {
        WorkflowItemDto item = WorkflowItemDto.from(def);
        return new WorkflowDetailDto(
                item.id(), item.name(), item.description(),
                item.enabled(), item.triggerTypes(), item.version(),
                def.triggers(), def.inputs(), def.steps(), def.metadata()
        );
    }
}
