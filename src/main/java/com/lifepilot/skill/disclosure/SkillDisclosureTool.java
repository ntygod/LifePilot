package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.registry.SkillRegistry;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;

/**
 * Skill 渐进式披露工具 — 注册 load_skill 工具到 DynamicToolRegistry。
 *
 * <p>支持三级渐进式披露：
 * <ul>
 *   <li>L0（system prompt 常驻）：紧凑的 id(name) 列表，由 ContextAssembler 生成</li>
 *   <li>L1（按需搜索）：action="search" 模式，返回匹配 skill 的 id + name + description</li>
 *   <li>L2（确定后加载）：action="load" 模式，返回完整 instructions 和建议工具</li>
 * </ul>
 * Skill 不存在时返回提示信息，引导 LLM 调用 generate_skill（HIGH 风险）
 * 走护栏确认流程，确保所有自扩展都经过用户确认。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class SkillDisclosureTool {

    private static final Logger log = LoggerFactory.getLogger(SkillDisclosureTool.class);
    private static final int MAX_SKILLS_PER_CALL = 3;
    /** L1 搜索模式默认返回的最大结果数。 */
    private static final int SEARCH_TOP_K = 5;

    private final DynamicToolRegistry toolRegistry;
    private final SkillActivator skillActivator;
    private final SkillRegistry skillRegistry;

    public SkillDisclosureTool(DynamicToolRegistry toolRegistry,
                               SkillActivator skillActivator,
                               SkillRegistry skillRegistry) {
        this.toolRegistry = toolRegistry;
        this.skillActivator = skillActivator;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 注册 load_skill 工具到 DynamicToolRegistry。
     *
     * <p>支持两种 action：
     * <ul>
     *   <li>search — L1 语义搜索，根据 query 返回匹配的 skill 元数据</li>
     *   <li>load（默认）— L2 加载，根据 skill_ids 返回完整指令和建议工具</li>
     * </ul>
     */
    public void registerTools() {
        var inputSchema = JsonSchema.of(Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "操作类型：load（加载完整指南，默认）或 search（搜索匹配的技能）",
                                "enum", List.of("load", "search")
                        ),
                        "skill_ids", Map.of(
                                "type", "array",
                                "description", "要加载的 Skill ID 列表（action=load 时使用），最多 3 个",
                                "items", Map.of("type", "string")
                        ),
                        "query", Map.of(
                                "type", "string",
                                "description", "搜索查询（action=search 时使用），描述你需要的能力"
                        )
                )
        ));

        BuiltinTool loadSkillTool = BuiltinTool.builder()
                .id("load_skill")
                .name("加载 Skill 指南")
                .description("加载 Skill 完整指南或搜索匹配的技能。"
                        + "action=search 时根据 query 搜索匹配技能，返回 ID 和描述。"
                        + "action=load 时根据 skill_ids 加载完整操作指南和建议工具。")
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
     * 处理 load_skill 工具调用 — 根据 action 参数分派到 search 或 load。
     *
     * <p>action 默认为 "load"，保持向后兼容。</p>
     */
    private ToolResult handleLoadSkill(ToolInput input) {
        String action = input.getOptionalParam("action", String.class).orElse("load");
        return switch (action) {
            case "search" -> handleSearchSkills(input);
            case "load" -> handleLoadSkillIds(input);
            default -> ToolResult.error("未知 action: " + action + "，支持 load 或 search");
        };
    }

    /**
     * L1 搜索 — 根据 query 语义搜索匹配的 Skill，返回 id + name + description。
     */
    private ToolResult handleSearchSkills(ToolInput input) {
        String query = input.getOptionalParam("query", String.class).orElse("");
        if (query.isBlank()) {
            return ToolResult.error("search 模式需要提供 query 参数");
        }

        var matches = skillRegistry.search(query, SEARCH_TOP_K);
        if (matches.isEmpty()) {
            return ToolResult.success(Map.of(
                    "found", 0,
                    "skills", List.of(),
                    "hint", "未找到匹配的技能。尝试换个关键词，或直接使用基础工具处理。"
            ));
        }

        var skillList = matches.stream()
                .map(s -> Map.<String, Object>of(
                        "skill_id", s.id(),
                        "name", s.name(),
                        "description", s.description() != null ? s.description() : ""
                ))
                .toList();
        return ToolResult.success(Map.of(
                "found", skillList.size(),
                "skills", skillList,
                "hint", "找到匹配技能。使用 load_skill(skill_ids=[\"skill-id\"]) 加载完整指南。"
        ));
    }

    /**
     * L2 加载 — 通过 SkillActivator 激活指定 Skill，返回完整指令和建议工具。
     *
     * <p>Skill 不存在时返回引导提示，引导 LLM 调用 generate_skill。</p>
     */
    private ToolResult handleLoadSkillIds(ToolInput input) {
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
