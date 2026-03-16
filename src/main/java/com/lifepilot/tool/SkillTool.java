package com.lifepilot.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skill 声明式工具（Layer 2）— 通过 SkillActivator 委托激活。
 *
 * <p>作为 {@link ToolContract} sealed interface 的 permit 之一，
 * 用于在 {@link com.lifepilot.tool.registry.DynamicToolRegistry} 中
 * 标识 Skill 来源的工具。{@code execute()} 委托给
 * {@link SkillActivator#activate(String)}，返回包含 skill_id、
 * instructions、suggested_tools 的 {@link ToolResult}。</p>
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
 * @param skillId 关联的 Skill ID
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
        List<String> tags,
        String skillId
) implements ToolContract {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    /** 全局 SkillActivator 引用，启动时由 ToolAutoConfiguration 注入。 */
    private static final AtomicReference<SkillActivator> ACTIVATOR_REF = new AtomicReference<>();

    /**
     * 设置全局 SkillActivator 引用。
     *
     * @param activator SkillActivator 实例
     */
    public static void setSkillActivator(SkillActivator activator) {
        ACTIVATOR_REF.set(activator);
        log.info("SkillActivator 已注入 SkillTool");
    }

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
        SkillActivator activator = ACTIVATOR_REF.get();
        if (activator == null) {
            log.error("SkillActivator 未初始化, skillId={}", skillId);
            return ToolResult.error("SkillActivator 未初始化");
        }

        try {
            SkillActivation activation = activator.activate(skillId);
            return ToolResult.success(Map.of(
                    "skill_id", activation.skillId(),
                    "instructions", activation.instructions(),
                    "suggested_tools", activation.suggestedTools()
            ));
        } catch (SkillActivationException e) {
            log.warn("Skill 激活失败: skillId={}, error={}", skillId, e.getMessage());
            return ToolResult.error(e.getMessage());
        }
    }
}
