package com.lifepilot.skill.bridge;

import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Skill 工具桥接 — 注册统一的 skills 工具到 DynamicToolRegistry。
 *
 * <p>注册一个 ID 为 "skills" 的 BuiltinTool，通过 action 参数分发操作：
 * <ul>
 *   <li>{@code list_skills} — 返回所有已注册 Skill 的 Discovery 摘要</li>
 *   <li>{@code activate_skill} — 激活指定 Skill，返回指令和建议工具</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class SkillToToolBridge {

    private static final Logger log = LoggerFactory.getLogger(SkillToToolBridge.class);

    private final DynamicToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final SkillActivator skillActivator;

    public SkillToToolBridge(DynamicToolRegistry toolRegistry,
                             SkillRegistry skillRegistry,
                             SkillActivator skillActivator) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.skillActivator = skillActivator;
    }

    /**
     * 注册统一的 skills 工具到 DynamicToolRegistry。
     *
     * <p>工具 ID 为 "skills"，支持 list_skills 和 activate_skill 两种操作。</p>
     */
    public void registerSkillsTool() {
        BuiltinTool skillsTool = BuiltinTool.builder()
                .id("skills")
                .name("Skill 管理")
                .description("发现和激活 Skill。list_skills 查看可用 Skill，activate_skill 激活指定 Skill。")
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .tags(List.of("skill"))
                .executor(this::handleSkillAction)
                .build();
        toolRegistry.registerBuiltinTool(skillsTool);
        log.info("统一 skills 工具注册成功");
    }

    /**
     * 根据 action 参数分发操作。
     *
     * @param input 工具输入
     * @return 工具执行结果
     */
    private ToolResult handleSkillAction(ToolInput input) {
        String action = input.getParam("action", String.class);
        return switch (action) {
            case "list_skills" -> listSkills();
            case "activate_skill" -> activateSkill(input);
            default -> ToolResult.error("未知操作: " + action);
        };
    }

    /**
     * 列出所有已注册 Skill 的 Discovery 摘要。
     *
     * @return 包含摘要列表的成功结果
     */
    private ToolResult listSkills() {
        List<String> summaries = skillRegistry.listSummaries();
        return ToolResult.success(Map.of("skills", summaries));
    }

    /**
     * 激活指定 Skill，返回指令和建议工具。
     *
     * @param input 工具输入（需包含 skill_id 参数）
     * @return 激活成功返回指令和建议工具，失败返回错误信息
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
            return ToolResult.error("Skill 不存在或无法激活: " + skillId);
        }
    }
}
