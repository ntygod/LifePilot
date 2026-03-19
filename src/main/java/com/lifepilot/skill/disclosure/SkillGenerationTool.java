package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.generation.SkillGap;
import com.lifepilot.skill.generation.SkillGapDetector;
import com.lifepilot.skill.generation.SkillGenerator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Skill 生成工具 — 注册独立的 generate_skill 工具到 DynamicToolRegistry。
 *
 * <p>允许 LLM 主动根据需求描述生成新 Skill。风险等级为 HIGH，
 * 护栏系统会在执行前自动向用户发起确认请求。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class SkillGenerationTool {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerationTool.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillGapDetector gapDetector;
    private final SkillGenerator skillGenerator;

    public SkillGenerationTool(DynamicToolRegistry toolRegistry,
                               SkillGapDetector gapDetector,
                               SkillGenerator skillGenerator) {
        this.toolRegistry = toolRegistry;
        this.gapDetector = gapDetector;
        this.skillGenerator = skillGenerator;
    }

    /**
     * 注册 generate_skill 工具到 DynamicToolRegistry。
     */
    public void registerTools() {
        var inputSchema = JsonSchema.of(Map.of(
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
                        + "生成成功后自动持久化并注册，可通过 load_skill 加载使用。")
                .inputSchema(inputSchema)
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .category(ToolCategory.EXTENSION)
                .tags(List.of("skill", "generation"))
                .executor(this::handleGenerateSkill)
                .build();

        toolRegistry.registerBuiltinTool(generateTool);
        log.info("generate_skill 工具注册成功（HIGH 风险）");
    }

    /**
     * 处理 generate_skill 工具调用 — 主动生成新 Skill。
     *
     * <p>此工具风险等级为 HIGH，护栏系统会在执行前自动向用户发起确认。
     * 用户确认后才会进入此方法，生成成功即持久化注册。</p>
     */
    @SuppressWarnings("unchecked")
    private ToolResult handleGenerateSkill(ToolInput input) {
        String description = input.getParam("description", String.class);
        String suggestedName = input.getOptionalParam("suggested_name", String.class)
                .orElse(description);
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

        log.info("开始生成 Skill: suggestedId={}, trigger={}", gap.suggestedId(), gap.triggerRequest());

        var result = skillGenerator.generate(gap);
        if (!result.success() || result.definition() == null) {
            String reason = result.errorMessage() != null ? result.errorMessage() : "生成失败";
            log.warn("Skill 生成失败: suggestedId={}, reason={}", gap.suggestedId(), reason);
            return ToolResult.error("Skill 生成失败: " + reason);
        }

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
                "suggested_tools", definition.suggestedTools(),
                "status", "registered",
                "message", "Skill 已生成并注册，可通过 load_skill 加载使用"
        ));
    }
}
