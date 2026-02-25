package com.lifepilot.skill.validation;

import com.lifepilot.skill.config.SkillConfigProperties;
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
 *   <li>allowed-tools 中每个工具 ID 在已注册工具集合中存在</li>
 *   <li>不包含 HIGH 或 CRITICAL 风险等级的工具</li>
 *   <li>记忆写权限必须设置 require-approval 为 true</li>
 *   <li>预算不超过配置上限</li>
 *   <li>system-prompt 不包含 Prompt 注入模式</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityValidator.class);

    /** 安全常量：max-cost-cents 上限。 */
    private static final int MAX_COST_CENTS = 100;

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
    private final SkillConfigProperties config;

    public SecurityValidator(DynamicToolRegistry toolRegistry, SkillConfigProperties config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
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

        // 1. 校验 allowed-tools 存在性和风险等级
        validateAllowedTools(skill, errors);

        // 2. 校验记忆写权限
        validateMemoryWriteApproval(skill, errors);

        // 3. 校验预算上限
        validateBudgetLimits(skill, errors);

        // 4. 检测 Prompt 注入
        validateSystemPrompt(skill, errors);

        boolean passed = errors.isEmpty();
        if (passed) {
            log.debug("安全验证通过");
        } else {
            log.debug("安全验证失败: 错误数={}", errors.size());
        }
        return new SecurityValidationResult(passed, errors);
    }

    /**
     * 校验 allowed-tools：工具存在性 + 风险等级。
     */
    private void validateAllowedTools(Map<String, Object> skill, List<String> errors) {
        Object toolsObj = skill.get("allowed-tools");
        if (!(toolsObj instanceof List<?> toolsList)) {
            // allowed-tools 不存在或非列表，FormatValidator 已校验，此处跳过
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
     * 校验记忆写权限：require-approval 必须为 true。
     */
    @SuppressWarnings("unchecked")
    private void validateMemoryWriteApproval(Map<String, Object> skill, List<String> errors) {
        Object memoryAccessObj = skill.get("memory-access");
        if (!(memoryAccessObj instanceof Map<?, ?> rawMemoryAccess)) {
            // memory-access 不存在，可选字段，跳过
            return;
        }
        Map<String, Object> memoryAccess = (Map<String, Object>) rawMemoryAccess;

        Object writeObj = memoryAccess.get("write");
        if (writeObj == null) {
            // 无 write 节点，跳过
            return;
        }

        // write 节点存在，检查 require-approval
        if (writeObj instanceof Map<?, ?> rawWrite) {
            Map<String, Object> write = (Map<String, Object>) rawWrite;
            Object requireApproval = write.get("require-approval");
            if (!Boolean.TRUE.equals(requireApproval)) {
                errors.add("记忆写权限必须设置 require-approval 为 true");
            }
        } else {
            // write 节点不是 Map，视为缺少 require-approval
            errors.add("记忆写权限必须设置 require-approval 为 true");
        }
    }

    /**
     * 校验预算上限。
     */
    @SuppressWarnings("unchecked")
    private void validateBudgetLimits(Map<String, Object> skill, List<String> errors) {
        Object budgetObj = skill.get("budget");
        if (!(budgetObj instanceof Map<?, ?> rawBudget)) {
            // budget 不存在，可选字段，跳过
            return;
        }
        Map<String, Object> budget = (Map<String, Object>) rawBudget;

        var validation = config.getValidation();

        // max-tokens
        checkBudgetLimit(budget, "max-tokens", validation.getAutoGeneratedMaxTokens(), errors);
        // max-steps
        checkBudgetLimit(budget, "max-steps", validation.getAutoGeneratedMaxSteps(), errors);
        // timeout-seconds
        checkBudgetLimit(budget, "timeout-seconds", validation.getAutoGeneratedMaxTimeout(), errors);
        // max-cost-cents（安全常量，不可配置）
        checkBudgetLimit(budget, "max-cost-cents", MAX_COST_CENTS, errors);
    }

    /**
     * 检查单个预算字段是否超过上限。
     */
    private void checkBudgetLimit(Map<String, Object> budget, String key, int limit, List<String> errors) {
        Object valueObj = budget.get(key);
        if (valueObj instanceof Number number) {
            if (number.intValue() > limit) {
                errors.add(key + " 超过上限: " + number.intValue() + " > " + limit);
            }
        }
    }

    /**
     * 检测 system-prompt 中的 Prompt 注入。
     */
    private void validateSystemPrompt(Map<String, Object> skill, List<String> errors) {
        Object promptObj = skill.get("system-prompt");
        if (!(promptObj instanceof String systemPrompt)) {
            return;
        }
        if (containsPromptInjection(systemPrompt)) {
            errors.add("system-prompt 包含疑似 Prompt 注入模式");
        }
    }

    /**
     * 检测字符串是否包含 Prompt 注入模式。
     *
     * @param systemPrompt 待检测的 system-prompt 内容
     * @return true 表示检测到注入模式
     */
    boolean containsPromptInjection(String systemPrompt) {
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(systemPrompt).find()) {
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
