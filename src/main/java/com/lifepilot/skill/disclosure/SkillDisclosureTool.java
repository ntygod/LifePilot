package com.lifepilot.skill.disclosure;

import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.model.ToolTier;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;

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
    private static final int MAX_SKILLS_PER_CALL = 3;

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
                "required", List.of("skill_ids"),
                "properties", Map.of(
                        "skill_ids", Map.of(
                                "type", "array",
                                "description", "要加载的 Skill ID 列表，从 system prompt 中的能力清单获取，最多 3 个",
                                "items", Map.of(
                                        "type", "string",
                                        "description", "Skill ID"
                                )
                        )
                )
        ));

        BuiltinTool loadSkillTool = BuiltinTool.builder()
                .id("load_skill")
                .name("加载 Skill 指南")
                .tier(ToolTier.CORE)
                .description("根据 Skill ID 列表加载完整操作指南和建议工具。"
                        + "从 system prompt 的能力清单中选择 skill_ids 调用。")
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
        List<String> skillIds = normalizeSkillIds(input);
        if (skillIds.isEmpty()) {
            return ToolResult.error("skill_ids 不能为空");
        }
        if (skillIds.size() > MAX_SKILLS_PER_CALL) {
            return ToolResult.error("一次最多只能加载 %d 个 Skill".formatted(MAX_SKILLS_PER_CALL));
        }

        var loadedSkills = new ArrayList<Map<String, Object>>();
        var missingSkills = new ArrayList<String>();
        var suggestedTools = new LinkedHashSet<String>();

        for (String skillId : skillIds) {
            try {
                SkillActivation activation = skillActivator.activate(skillId);
                loadedSkills.add(Map.of(
                        "skill_id", activation.skillId(),
                        "instructions", activation.instructions(),
                        "suggested_tools", activation.suggestedTools()
                ));
                suggestedTools.addAll(activation.suggestedTools());
            } catch (SkillActivationException e) {
                missingSkills.add(skillId);
            }
        }

        if (loadedSkills.isEmpty()) {
            return handleSkillNotFound(String.join(", ", missingSkills));
        }

        // 将激活的 Skill 建议工具注入到 Agent 循环上下文，
        // 使其在后续迭代中通过工具分层过滤变为可见
        if (!suggestedTools.isEmpty()) {
            var loopContextRef = input.getContextValue(
                    ToolContextKeys.LOOP_CONTEXT_REF, AgentLoopContext.class);
            if (loopContextRef.isPresent()) {
                loopContextRef.get().addActivatedSkillTools(suggestedTools);
                log.debug("已激活 Skill 工具: count={}, tools={}", suggestedTools.size(), suggestedTools);
            }
        }

        var data = Map.of(
                "skills", List.copyOf(loadedSkills),
                "loaded_count", loadedSkills.size(),
                "missing_skills", List.copyOf(missingSkills),
                "all_suggested_tools", List.copyOf(suggestedTools)
        );
        if (!missingSkills.isEmpty()) {
            return ToolResult.partialSuccess(data,
                    "部分 Skill 不存在: " + String.join(", ", missingSkills));
        }
        return ToolResult.success(data);
    }

    @SuppressWarnings("unchecked")
    private List<String> normalizeSkillIds(ToolInput input) {
        Object rawSkillIds = input.parameters().get("skill_ids");
        if (rawSkillIds instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(skillId -> !skillId.isBlank())
                    .toList();
        }
        return List.of();
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
