package com.lifepilot.workflow.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.Builder;

/**
 * 工作流定义 record。
 *
 * <p>从 YAML 文件解析的不可变数据载体，描述工作流的完整蓝图，
 * 包含步骤列表、触发器、输入参数和元数据。
 *
 * <p>紧凑构造器对 {@code id}、{@code name}、{@code steps} 执行非空校验，
 * 对集合字段执行防御性拷贝（{@link List#copyOf} / {@link Map#copyOf}）。
 *
 * @param id          工作流唯一标识
 * @param name        工作流名称
 * @param description 工作流描述
 * @param version     版本号
 * @param enabled     是否启用
 * @param triggers    触发器列表
 * @param inputs      输入参数定义（参数名 → 参数定义）
 * @param steps       步骤列表（按执行顺序排列）
 * @param metadata    自定义元数据
 * @author zsg
 * @since 2026-02-26
 */
@Builder(toBuilder = true)
public record WorkflowDefinition(
        String id,
        String name,
        String description,
        String version,
        boolean enabled,
        List<WorkflowTrigger> triggers,
        Map<String, WorkflowInputParam> inputs,
        List<WorkflowStep> steps,
        Map<String, String> metadata
) {

    /**
     * 紧凑构造器：非空校验 + 集合防御性拷贝。
     */
    public WorkflowDefinition {
        Objects.requireNonNull(id, "工作流 id 不能为空");
        Objects.requireNonNull(name, "工作流 name 不能为空");
        Objects.requireNonNull(steps, "工作流 steps 不能为空");
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        steps = List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
