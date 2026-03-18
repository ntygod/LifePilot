package com.lifepilot.skill.bridge;

import com.lifepilot.meta.convenience.CapabilityAggregator;
import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.generation.SkillGap;
import com.lifepilot.skill.generation.SkillGapDetector;
import com.lifepilot.skill.generation.SkillGenerator;
import com.lifepilot.skill.generation.SkillGenerator.GenerationResult;
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
 * Skill 工具桥接 — 注册统一的 skills 工具到 DynamicToolRegistry。
 *
 * <p>注册一个 ID 为 "skills" 的 BuiltinTool，通过 action 参数分发操作：
 * <ul>
 *   <li>{@code list_skills} — 返回所有已注册 Skill 的 Discovery 摘要</li>
 *   <li>{@code activate_skill} — 激活指定 Skill，找不到时自动触发缺口检测与生成</li>
 *   <li>{@code generate_skill} — 根据需求描述主动生成新 Skill</li>
 * </ul>
 *
 * <p>确认机制：{@code generate_skill} 操作的风险等级为 {@link RiskLevel#HIGH}，
 * 护栏系统（{@code ToolExecutionPipeline} → {@code GuardrailEngine}）会在执行前
 * 自动向用户发起确认请求。用户确认后才会执行生成，生成成功即持久化注册。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class SkillToToolBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillToToolBridge.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillActivator skillActivator;
    private final CapabilityAggregator capabilityAggregator;
    @Nullable private final SkillGapDetector gapDetector;
    @Nullable private final SkillGenerator skillGenerator;

    public SkillToToolBridge(DynamicToolRegistry toolRegistry,
                             SkillActivator skillActivator,
                             CapabilityAggregator capabilityAggregator,
                             @Nullable SkillGapDetector gapDetector,
                             @Nullable SkillGenerator skillGenerator) {
        this.toolRegistry = toolRegistry;
        this.skillActivator = skillActivator;
        this.capabilityAggregator = capabilityAggregator;
        this.gapDetector = gapDetector;
        this.skillGenerator = skillGenerator;
    }

    /**
     * 注册统一的 skills 工具到 DynamicToolRegistry。
     *
     * <p>注册两个工具：
     * <ul>
     *   <li>{@code skills} — 查询和激活 Skill（MEDIUM 风险，自动执行+审计）</li>
     *   <li>{@code generate_skill} — 生成新 Skill（HIGH 风险，需用户确认）</li>
     * </ul>
     */
    public void registerSkillsTool() {
        // 1. skills 工具：list + activate（MEDIUM 风险）
        var skillsSchema = JsonSchema.of(Map.of(
                "type", "object",
                "required", List.of("action"),
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "操作类型：list_skills / activate_skill"
                        ),
                        "skill_id", Map.of(
                                "type", "string",
                                "description", "Skill ID（activate_skill 时必填）"
                        )
                )
        ));

        BuiltinTool skillsTool = BuiltinTool.builder()
                .id("skills")
                .name("Skill 管理")
                .description("发现和激活 Skill。"
                        + "list_skills 查看可用 Skill；"
                        + "activate_skill 激活指定 Skill（找不到时自动触发生成）。")
                .inputSchema(skillsSchema)
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .category(ToolCategory.EXTENSION)
                .tags(List.of("skill"))
                .executor(this::handleSkillAction)
                .build();
        toolRegistry.registerBuiltinTool(skillsTool);

        // 2. generate_skill 工具：主动生成（HIGH 风险，护栏自动确认）
        var generateSchema = JsonSchema.of(Map.of(
                "type", "object",
                "required", List.of("description"),
                "properties", Map.of(
                        "description", Map.of(
                                "type", "string",
                                "description", "需求描述，描述希望生成的 Skill 功能"
                        ),
                        "suggested_name", Map.of(
                                "type", "string",
                                "description", "建议的 Skill 名称（可选）"
                        ),
                        "suggested_tools", Map.of(
                                "type", "array",
                                "description", "建议使用的工具 ID 列表（可选）"
                        )
                )
        ));

        BuiltinTool generateTool = BuiltinTool.builder()
                .id("generate_skill")
                .name("生成 Skill")
                .description("根据需求描述自动生成新 Skill。"
                        + "生成成功后自动持久化并注册，可通过 activate_skill 激活使用。")
                .inputSchema(generateSchema)
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .category(ToolCategory.EXTENSION)
                .tags(List.of("skill", "generation"))
                .executor(this::handleGenerateSkill)
                .build();
        toolRegistry.registerBuiltinTool(generateTool);

        log.info("Skill 工具注册成功: skills(查询/激活) + generate_skill(生成, HIGH 风险)");
    }

    /**
     * 根据 action 参数分发 skills 工具操作。
     */
    private ToolResult handleSkillAction(ToolInput input) {
        String action = input.getParam("action", String.class);
        return switch (action) {
            case "list_skills" -> listSkills();
            case "activate_skill" -> activateSkill(input);
            default -> ToolResult.error("未知操作: " + action + "，支持: list_skills / activate_skill");
        };
    }

    // ==================== list_skills ====================

    /**
     * 列出所有已注册 Skill — 委托 CapabilityAggregator 统一数据来源。
     */
    private ToolResult listSkills() {
        var capabilities = capabilityAggregator.filterByType("skill");
        var summaries = capabilities.stream()
                .map(cap -> cap.id() + ": " + cap.description())
                .toList();
        return ToolResult.success(Map.of("skills", summaries));
    }

    // ==================== activate_skill（含被动触发） ====================

    /**
     * 激活指定 Skill，返回指令和建议工具。
     *
     * <p>被动触发路径：当 Skill 不存在时，若自扩展管线可用，
     * 自动执行缺口检测 → 生成 → 持久化注册 → 返回激活结果。</p>
     */
    private ToolResult activateSkill(ToolInput input) {
        String skillId = input.getParam("skill_id", String.class);
        try {
            SkillActivation activation = skillActivator.activate(skillId);
            return ToolResult.success(Map.of(
                    "skill_id", activation.skillId(),
                    "instructions", activation.instructions(),
                    "suggested_tools", activation.suggestedTools()
            ));
        } catch (SkillActivationException e) {
            // Skill 不存在 → 尝试被动触发自扩展
            if (gapDetector == null || skillGenerator == null) {
                return ToolResult.error("Skill 不存在或无法激活: " + skillId);
            }

            log.info("Skill 激活失败，触发被动自扩展: skillId={}", skillId);
            var gapOpt = gapDetector.detectGap(skillId);
            if (gapOpt.isEmpty()) {
                return ToolResult.error("Skill 不存在且未检测到能力缺口: " + skillId);
            }

            return generateAndPersist(gapOpt.get());
        }
    }

    // ==================== generate_skill（主动触发，HIGH 风险） ====================

    /**
     * 处理 generate_skill 工具调用 — 主动生成新 Skill。
     *
     * <p>此工具风险等级为 HIGH，护栏系统会在执行前自动向用户发起确认。
     * 用户确认后才会进入此方法，生成成功即持久化注册。</p>
     */
    private ToolResult handleGenerateSkill(ToolInput input) {
        if (skillGenerator == null || gapDetector == null) {
            return ToolResult.error("Skill 自扩展功能未启用，请检查配置 lifepilot.skills.auto-generation.enabled");
        }

        String description = input.getParam("description", String.class);
        String suggestedName = input.getOptionalParam("suggested_name", String.class)
                .orElse(description);
        @SuppressWarnings("unchecked")
        List<String> suggestedTools = input.getOptionalParam("suggested_tools", List.class)
                .orElse(List.of());

        // 构建 SkillGap（主动触发，置信度 1.0）
        var gap = new SkillGap(
                1.0,
                "auto-" + System.currentTimeMillis(),
                suggestedName,
                description,
                suggestedTools,
                "用户主动请求生成"
        );

        return generateAndPersist(gap);
    }

    // ==================== 内部辅助 ====================

    /**
     * 执行生成 → 持久化 → 注册的完整流程。
     *
     * <p>生成成功后直接调用 {@link SkillGenerator#confirmAndPersist} 持久化并注册，
     * 无需额外的确认步骤（主动路径由护栏 HIGH 风险确认，被动路径由 activate 触发）。</p>
     */
    private ToolResult generateAndPersist(SkillGap gap) {
        // 防御性检查（调用方已保证非 null）
        if (skillGenerator == null) {
            return ToolResult.error("Skill 自扩展功能未启用");
        }

        log.info("开始生成 Skill: suggestedId={}, trigger={}", gap.suggestedId(), gap.triggerRequest());

        GenerationResult result = skillGenerator.generate(gap);

        if (!result.success() || result.definition() == null) {
            String reason = result.errorMessage() != null
                    ? result.errorMessage()
                    : "生成失败（验证未通过）";
            log.warn("Skill 生成失败: suggestedId={}, reason={}", gap.suggestedId(), reason);
            return ToolResult.error("Skill 生成失败: " + reason);
        }

        // 生成成功 → 直接持久化并注册（userConfirmed=true）
        var definition = result.definition();
        boolean persisted = skillGenerator.confirmAndPersist(definition);
        if (!persisted) {
            log.warn("Skill 持久化失败: skillId={}", definition.id());
            return ToolResult.error("Skill 生成成功但持久化失败: " + definition.id());
        }

        log.info("Skill 生成并注册成功: skillId={}, name={}", definition.id(), definition.name());
        return ToolResult.success(Map.of(
                "skill_id", definition.id(),
                "name", definition.name(),
                "description", definition.description(),
                "instructions_preview", truncate(definition.instructions(), 500),
                "suggested_tools", definition.suggestedTools(),
                "status", "registered",
                "message", "Skill 已生成并注册，可通过 activate_skill 激活使用"
        ));
    }

    /** 截断文本到指定长度。 */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
