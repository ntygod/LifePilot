package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.generation.SkillGapDetector;
import com.lifepilot.skill.generation.SkillGenerator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Skill 渐进式披露工具 — 注册 load_skill 工具到 DynamicToolRegistry。
 *
 * <p>L2 按需加载：LLM 根据 system prompt 中的 L1 清单，
 * 调用 load_skill 获取指定 Skill 的完整操作指南和建议工具。
 * Skill 不存在时触发被动自扩展（若自扩展管线可用）。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class SkillDisclosureTool {

    private static final Logger log = LoggerFactory.getLogger(SkillDisclosureTool.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillActivator skillActivator;
    private final SkillMetricsTracker metricsTracker;
    @Nullable private final SkillGapDetector gapDetector;
    @Nullable private final SkillGenerator skillGenerator;

    public SkillDisclosureTool(DynamicToolRegistry toolRegistry,
                               SkillActivator skillActivator,
                               SkillMetricsTracker metricsTracker,
                               @Nullable SkillGapDetector gapDetector,
                               @Nullable SkillGenerator skillGenerator) {
        this.toolRegistry = toolRegistry;
        this.skillActivator = skillActivator;
        this.metricsTracker = metricsTracker;
        this.gapDetector = gapDetector;
        this.skillGenerator = skillGenerator;
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
     * 被动自扩展触发 — Skill 不存在时自动检测缺口并生成。
     *
     * @param skillId 未找到的 Skill ID
     * @return 生成结果或错误信息
     */
    private ToolResult handleSkillNotFound(String skillId) {
        if (gapDetector == null || skillGenerator == null) {
            return ToolResult.error("Skill 不存在: " + skillId);
        }

        log.info("Skill 不存在，触发被动自扩展: skillId={}", skillId);
        var gapOpt = gapDetector.detectGap(skillId);
        if (gapOpt.isEmpty()) {
            return ToolResult.error("Skill 不存在且未检测到能力缺口: " + skillId);
        }

        var gap = gapOpt.get();
        var result = skillGenerator.generate(gap);
        if (!result.success() || result.definition() == null) {
            String reason = result.errorMessage() != null ? result.errorMessage() : "生成失败";
            return ToolResult.error("Skill 自动生成失败: " + reason);
        }

        boolean persisted = skillGenerator.confirmAndPersist(result.definition());
        if (!persisted) {
            return ToolResult.error("Skill 生成成功但注册失败: " + skillId);
        }

        var def = result.definition();
        return ToolResult.success(Map.of(
                "skill_id", def.id(),
                "name", def.name(),
                "description", def.description(),
                "status", "auto_generated",
                "message", "已自动生成并注册新 Skill，请重新调用 load_skill 获取完整指南"
        ));
    }
}
