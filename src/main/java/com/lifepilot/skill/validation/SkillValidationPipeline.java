package com.lifepilot.skill.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skill 验证管线 — 格式验证 → 安全验证 → 沙箱验证，任一阶段失败即终止。
 *
 * <p>按顺序执行三重验证：
 * <ol>
 *   <li>{@link FormatValidator} — 校验 SKILL.md 格式（YAML Frontmatter + Markdown Body）</li>
 *   <li>{@link SecurityValidator} — 校验工具白名单、风险等级、预算上限和 Prompt 注入</li>
 *   <li>{@link SandboxValidator} — 在隔离环境中验证内部一致性</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillValidationPipeline {

    private static final Logger log = LoggerFactory.getLogger(SkillValidationPipeline.class);

    private final FormatValidator formatValidator;
    private final SecurityValidator securityValidator;
    private final SandboxValidator sandboxValidator;

    public SkillValidationPipeline(FormatValidator formatValidator,
                                   SecurityValidator securityValidator,
                                   SandboxValidator sandboxValidator) {
        this.formatValidator = formatValidator;
        this.securityValidator = securityValidator;
        this.sandboxValidator = sandboxValidator;
    }

    /**
     * 执行三重验证。
     *
     * <p>按顺序执行格式验证 → 安全验证 → 沙箱验证，任一阶段失败时终止管线并返回失败结果。
     * 每个阶段开始/结束记录 DEBUG 日志，最终结果记录 INFO 日志。</p>
     *
     * @param markdownContent SKILL.md 完整文本
     * @return 验证结果
     */
    public SkillValidationResult validate(String markdownContent) {
        // 1. 格式验证
        log.debug("开始格式验证阶段");
        var formatResult = formatValidator.validate(markdownContent);
        log.debug("格式验证完成: passed={}", formatResult.passed());
        if (!formatResult.passed()) {
            log.info("验证管线终止于格式验证阶段: errors={}", formatResult.errors());
            return SkillValidationResult.failed(SkillValidationResult.ValidationStage.FORMAT, formatResult.errors());
        }

        // 2. 安全验证 — 使用格式验证阶段解析出的 Map
        log.debug("开始安全验证阶段");
        var securityResult = securityValidator.validate(formatResult.parsedMap());
        log.debug("安全验证完成: passed={}", securityResult.passed());
        if (!securityResult.passed()) {
            log.info("验证管线终止于安全验证阶段: errors={}", securityResult.errors());
            return SkillValidationResult.failed(SkillValidationResult.ValidationStage.SECURITY, securityResult.errors());
        }

        // 3. 沙箱验证
        log.debug("开始沙箱验证阶段");
        var sandboxResult = sandboxValidator.validate(markdownContent);
        log.debug("沙箱验证完成: passed={}", sandboxResult.passed());
        if (!sandboxResult.passed()) {
            log.info("验证管线终止于沙箱验证阶段: errors={}", sandboxResult.errors());
            return SkillValidationResult.failed(SkillValidationResult.ValidationStage.SANDBOX, sandboxResult.errors());
        }

        log.info("三重验证管线全部通过");
        return SkillValidationResult.allPassed();
    }
}
