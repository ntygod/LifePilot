package com.lifepilot.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;

import java.util.List;

/**
 * YAML 声明式工具（Layer 2）— 骨架实现。
 *
 * <p>从 YAML 配置文件加载，支持运行时热加载。
 * 本 spec 仅定义骨架，execute() 抛出 UnsupportedOperationException，
 * 在后续 YAML 工具 spec 中实现。</p>
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
public record YamlTool(
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
        return ToolLayer.YAML_DECLARATIVE;
    }

    @Override
    public boolean exportable() {
        return false;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        throw new UnsupportedOperationException("YAML 工具执行尚未实现");
    }
}
