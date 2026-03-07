package com.lifepilot.skill.validation;

import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 安全验证器 — 校验自生成 Skill 的安全约束。
 *
 * <p>校验规则：
 * <ul>
 *   <li>suggested-tools 中每个工具 ID 在已注册工具集合中存在</li>
 *   <li>不包含 HIGH 或 CRITICAL 风险等级的工具</li>
 *   <li>instructions 不包含 Prompt 注入模式</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityValidator.class);

    /** Prompt 注入检测模式。 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+previous\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+a"),
            Pattern.compile("(?i)disregard\\s+your\\s+rules"),
            Pattern.compile("(?i)override\\s+system"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)DAN\\s+mode")
    );

    private final DynamicToolRegistry toolRegistry;

    public SecurityValidator(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 校验 YAML 解析后的 Map 的安全性。
     *
     * @param yamlMap YAML 解析后的完整 Map（包含 "skill" 根键）
     * @return 校验结果
     */
    @SuppressWarnings("unchecked")
    public SecurityValidationResult validate(Map<String, Object> yamlMap) {
        List<String> errors = new ArrayList<>();

        Object skillObj = yamlMap.get("skill");
        if (!(skillObj instanceof Map<?, ?> rawSkill)) {
            return new SecurityValidationResult(false, List.of("缺少 skill 根节点"));
        }
        Map<String, Object> skill = (Map<String, Object>) rawSkill;

        // 1. 校验 suggested-tools 存在性和风险等级
        validateSuggestedTools(skill, errors);

        // 2. 检测 Prompt 注入
        validateInstructions(skill, errors);

        boolean passed = errors.isEmpty();
        if (passed) {
            log.debug("安全验证通过");
        } else {
            log.debug("安全验证失败: 错误数={}", errors.size());
        }
        return new SecurityValidationResult(passed, errors);
    }

    /**
     * 校验 suggested-tools：工具存在性 + 风险等级。
     */
    private void validateSuggestedTools(Map<String, Object> skill, List<String> errors) {
        Object toolsObj = skill.get("suggested-tools");
        if (!(toolsObj instanceof List<?> toolsList)) {
            // suggested-tools 不存在或非列表，FormatValidator 已校验，此处跳过
            return;
        }

        for (Object toolIdObj : toolsList) {
            String toolId = String.valueOf(toolIdObj);
            var toolOpt = toolRegistry.resolve(toolId);
            if (toolOpt.isEmpty()) {
                errors.add("工具不存在: " + toolId);
                continue;
            }
            ToolContract tool = toolOpt.get();
            if (tool.riskLevel().requiresConfirmation()) {
                errors.add("不允许使用 " + tool.riskLevel() + " 风险等级的工具: " + toolId);
            }
        }
    }

    /**
     * 检测 instructions 中的 Prompt 注入。
     */
    private void validateInstructions(Map<String, Object> skill, List<String> errors) {
        Object instructionsObj = skill.get("instructions");
        if (!(instructionsObj instanceof String instructions)) {
            return;
        }
        if (containsPromptInjection(instructions)) {
            errors.add("instructions 包含疑似 Prompt 注入模式");
        }
    }

    /**
     * 检测字符串是否包含 Prompt 注入模式。
     *
     * @param content 待检测的内容
     * @return true 表示检测到注入模式
     */
    boolean containsPromptInjection(String content) {
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全验证结果。
     *
     * @param passed 是否通过
     * @param errors 错误信息列表
     */
    public record SecurityValidationResult(boolean passed, List<String> errors) {
        /** 防御性拷贝。 */
        public SecurityValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
