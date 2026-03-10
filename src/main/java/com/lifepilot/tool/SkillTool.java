package com.lifepilot.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;

import java.util.List;

/**
 * Skill 声明式工具（Layer 2）— 工具注册表中的 Skill 工具类型标记。
 *
 * <p>作为 {@link ToolContract} sealed interface 的 permit 之一，
 * 用于在 {@link com.lifepilot.tool.registry.DynamicToolRegistry} 中
 * 标识 Skill 来源的工具。实际的 Skill 执行通过
 * {@code SkillActivator} 路径完成，不经过 {@code execute()} 方法。</p>
 *
 * @param id 工具唯一标识
 * @param name 工具显示名称
 * @param description 工具描述
 * @param inputSchema 输入参数 JSON Schema
 * @param outputSchema 输出类型 JSON Schema
 * @param riskLevel 风险等级
 * @param idempotent 是否幂等
 * @param budget 执行预算
 * @param tags 工具标签
 * @author zsg
 * @since 2026-02-24
 */
public record SkillTool(
        String id,
        String name,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        RiskLevel riskLevel,
        boolean idempotent,
        ToolBudget budget,
        List<String> tags
) implements ToolContract {

    @Override
    public ToolLayer layer() {
        return ToolLayer.SKILL_DECLARATIVE;
    }

    @Override
    public boolean exportable() {
        return false;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        throw new UnsupportedOperationException(
                "SkillTool 不支持直接执行，Skill 应通过 SkillActivator 路径调用");
    }
}