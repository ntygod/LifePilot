package com.lifepilot.skill.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 提供 {@code skill.load} BuiltinTool 元数据，委托 {@link SkillLoadToolExecutor} 执行。
 *
 * <p>{@code skill.load} 是 Skill 系统对 Agent 暴露的统一激活入口：
 * 单次调用可激活 1-3 个 Skill，返回替换占位符后的 SKILL.md 正文，
 * 同时合并各 Skill 声明的 {@code suggested_tools} 供后续轮次工具可见性提升。</p>
 *
 * <p>归类为 {@link ToolCategory#EXTENSION}（获取新能力），风险低、幂等、可并行。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class SkillLoadTool {

    private static final Logger log = LoggerFactory.getLogger(SkillLoadTool.class);

    private final SkillLoadToolExecutor executor;

    public SkillLoadTool(SkillLoadToolExecutor executor) {
        this.executor = executor;
    }

    /** 构建 {@code skill.load} 内置工具定义。 */
    public BuiltinTool tool() {
        return BuiltinTool.builder()
                .id("skill.load")
                .name("激活技能")
                .description("Activate one to three ZhiWei skills by name. Returns the SKILL.md body "
                        + "(with {skill_dir}/{references_dir}/{scripts_dir} placeholders resolved) and "
                        + "merges each skill's suggested_tools into the activated tool visibility set "
                        + "for subsequent turns.")
                .tags(List.of("skill", "activate", "load", "guide", "extension", "capability"))
                .category(ToolCategory.EXTENSION)
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "names", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Skill names to activate (1-3 allowed)"
                                )
                        ),
                        "required", List.of("names")
                )))
                .executor(this::run)
                .build();
    }

    private ToolResult run(ToolInput input) {
        try {
            Map<String, Object> result = executor.execute(input.parameters());
            return ToolResult.success(result);
        } catch (IllegalArgumentException e) {
            log.debug("skill.load 参数校验失败: {}", e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("skill.load 执行失败", e);
            return ToolResult.error("skill 激活失败: " + e.getMessage());
        }
    }
}
