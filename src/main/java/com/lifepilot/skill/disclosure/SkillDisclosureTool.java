package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Skill 渐进式披露工具 — 注册 load_skill 工具到 DynamicToolRegistry。
 *
 * <p>L2 按需加载：LLM 根据 system prompt 中的 L1 清单，
 * 调用 load_skill 获取指定 Skill 的完整操作指南和建议工具。
 * Skill 不存在时返回提示信息，引导 LLM 调用 generate_skill（HIGH 风险）
 * 走护栏确认流程，确保所有自扩展都经过用户确认。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class SkillDisclosureTool {

    private static final Logger log = LoggerFactory.getLogger(SkillDisclosureTool.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillActivator skillActivator;

    public SkillDisclosureTool(DynamicToolRegistry toolRegistry,
                               SkillActivator skillActivator) {
        this.toolRegistry = toolRegistry;
        this.skillActivator = skillActivator;
    }

    /**
     * 注册 load_skill 工具到 DynamicToolRegistry。
     */
    public void registerTools() {
        var inputSchema = JsonSchema.of(Map.of(
                "type", "object",
                "required", List.of("skill_id"),
                "properties", Map.of(
                        "skill_id", Map.of(
                                "type", "string",
                                "description", "要加载的 Skill ID，从 system prompt 中的能力清单获取"
                        )
                )
        ));

        BuiltinTool loadSkillTool = BuiltinTool.builder()
                .id("load_skill")
                .name("加载 Skill 指南")
                .description("根据 Skill ID 加载完整操作指南和建议工具。"
                        + "从 system prompt 的能力清单中选择 skill_id 调用。")
                .inputSchema(inputSchema)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .category(ToolCategory.EXTENSION)
                .tags(List.of("skill"))
                .executor(this::handleLoadSkill)
                .build();

        toolRegistry.registerBuiltinTool(loadSkillTool);
        log.info("load_skill 工具注册成功");
    }

    /**
     * 处理 load_skill 工具调用。
     *
     * <p>通过 SkillActivator 激活指定 Skill，返回指令和建议工具。
     * Skill 不存在时尝试被动自扩展。</p>
     */
    private ToolResult handleLoadSkill(ToolInput input) {
        String skillId = input.getParam("skill_id", String.class);

        try {
            SkillActivation activation = skillActivator.activate(skillId);
            return ToolResult.success(Map.of(
                    "skill_id", activation.skillId(),
                    "instructions", activation.instructions(),
                    "suggested_tools", activation.suggestedTools()
            ));
        } catch (SkillActivationException e) {
            // Skill 不存在 → 尝试被动自扩展
            return handleSkillNotFound(skillId);
        }
    }

    /**
     * Skill 不存在时返回提示 — 引导 LLM 调用 generate_skill 走护栏确认。
     *
     * <p>不再在 load_skill（LOW 风险）内部直接触发自扩展，
     * 所有 Skill 生成统一走 generate_skill（HIGH 风险）→ 护栏确认 → 用户知情。</p>
     *
     * @param skillId 未找到的 Skill ID
     * @return 包含引导提示的错误结果
     */
    private ToolResult handleSkillNotFound(String skillId) {
        log.info("Skill 不存在: skillId={}", skillId);
        return ToolResult.error("Skill 不存在: " + skillId
                + "。如果需要此能力，请调用 generate_skill 工具描述需求，系统将生成并注册新 Skill");
    }
}
