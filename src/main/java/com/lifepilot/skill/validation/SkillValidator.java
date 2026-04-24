package com.lifepilot.skill.validation;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.spec.SkillFrontmatter;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Skill 合一校验器 —— 合并原 FormatValidator / SecurityValidator / SandboxValidator。
 *
 * <p>两条路径（见 docs/skill-spec.md §5）：
 * <ul>
 *   <li>{@link #validate(ParsedSkill)}：预置 / 导入 / 市场 路径，宽松 ——
 *       description + body 硬约束校验 + secret 模式扫描 + 未知工具仅 WARN 不阻断</li>
 *   <li>{@link #validateGenerated(ParsedSkill)}：自生成路径，严格 ——
 *       额外强校验工具存在性 + 拒绝 HIGH / CRITICAL 风险工具</li>
 * </ul>
 *
 * <p>不改已有的 {@link SkillDescriptionValidator} / {@link SkillBodyValidator} /
 * {@link SkillRequirementGate}，只做组合与薄层编排。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillValidator.class);

    /** Body 侧疑似 secret 的正则模式集合（PEM 私钥 / API Key / 口令 / OpenAI-style token）。 */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]+PRIVATE KEY-----"),
            Pattern.compile("(?i)api[_-]?key\\s*[:=]\\s*['\"][a-zA-Z0-9_-]{20,}['\"]"),
            Pattern.compile("(?i)password\\s*[:=]\\s*['\"][^'\"]{4,}['\"]"),
            Pattern.compile("sk-[a-zA-Z0-9]{40,}")
    );

    private final SkillDescriptionValidator descriptionValidator;
    private final SkillBodyValidator bodyValidator;
    private final DynamicToolRegistry toolRegistry;

    public SkillValidator(SkillDescriptionValidator descriptionValidator,
                          SkillBodyValidator bodyValidator,
                          DynamicToolRegistry toolRegistry) {
        this.descriptionValidator = descriptionValidator;
        this.bodyValidator = bodyValidator;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 预置 / 导入 / 市场 路径校验。
     *
     * <p>{@code suggestedTools} 引用的工具不存在时只记 WARN，不抛异常 ——
     * 真正的工具可用性在加载期由 {@link SkillRequirementGate} 基于
     * {@code requires.tools} 做硬过滤。</p>
     *
     * @param parsed 已解析的 SKILL.md
     * @throws IllegalArgumentException description / body / secret 任一硬约束不通过
     */
    public void validate(ParsedSkill parsed) {
        validateCommon(parsed);
        for (String toolId : parsed.frontmatter().zhiweiMeta().suggestedTools()) {
            if (toolRegistry.resolve(toolId).isEmpty()) {
                log.warn("skill '{}' 引用了未知工具 '{}'（仅 WARN，不阻断）",
                        parsed.frontmatter().name(), toolId);
            }
        }
    }

    /**
     * 自生成路径校验（严格模式）。
     *
     * <p>额外规则：
     * <ul>
     *   <li>{@code suggestedTools} 必须全部已注册 —— LLM 不得自造工具</li>
     *   <li>禁止声明 {@link RiskLevel#HIGH} / {@link RiskLevel#CRITICAL} 风险工具 ——
     *       避免自动生成 skill 绕过用户确认直接执行高危操作</li>
     * </ul>
     *
     * @param parsed 已解析的 SKILL.md
     * @throws IllegalArgumentException 公共硬约束不通过 / 引用未知工具 / 引用高危工具
     */
    public void validateGenerated(ParsedSkill parsed) {
        validateCommon(parsed);
        for (String toolId : parsed.frontmatter().zhiweiMeta().suggestedTools()) {
            var toolOpt = toolRegistry.resolve(toolId);
            if (toolOpt.isEmpty()) {
                throw new IllegalArgumentException(
                        "自生成 skill '" + parsed.frontmatter().name()
                                + "' 引用了未知工具: " + toolId);
            }
            var tool = toolOpt.get();
            if (tool.riskLevel() == RiskLevel.HIGH || tool.riskLevel() == RiskLevel.CRITICAL) {
                throw new IllegalArgumentException(
                        "自生成 skill 不得声明 HIGH/CRITICAL 风险工具: " + toolId
                                + "（level=" + tool.riskLevel() + "）");
            }
        }
    }

    /** 公共硬约束：description 头/长度/禁工作流 + body 小节/长度 + secret 模式扫描。 */
    private void validateCommon(ParsedSkill parsed) {
        SkillFrontmatter fm = parsed.frontmatter();
        descriptionValidator.validate(fm.description());
        bodyValidator.validate(parsed.body());
        for (Pattern p : SECRET_PATTERNS) {
            if (p.matcher(parsed.body()).find()) {
                throw new IllegalArgumentException(
                        "body 命中疑似 secret 模式: " + p.pattern() + "（见 docs/skill-spec.md §5）");
            }
        }
    }
}
