package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Skill 定义校验器 — 在注册前校验 {@link SkillDefinition} 的合法性。
 *
 * <p>校验规则：
 * <ul>
 *   <li>ID 格式：{@code ^[a-z0-9.-]{1,64}$}（小写字母、数字、连字符和点号）</li>
 *   <li>名称长度 ≤ 配置的 maxNameLength</li>
 *   <li>Instructions 长度 ≤ 配置的 maxInstructionsLength</li>
 *   <li>suggestedTools 中每个工具 ID 在 {@link DynamicToolRegistry} 中存在（允许为空列表）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillDefinitionValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillDefinitionValidator.class);

    /** Skill ID 合法格式：小写字母、数字、连字符和点号，长度 1-64。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9.-]{1,64}$");

    private final DynamicToolRegistry toolRegistry;
    private final SkillConfigProperties.Validation validationConfig;
    /** 启动期标志：BuiltinTool 在 ApplicationReadyEvent 注册，早于此的校验把"工具未注册"当 DEBUG，避免启动日志刷屏假警告。 */
    private volatile boolean startupComplete = false;

    public SkillDefinitionValidator(DynamicToolRegistry toolRegistry,
                                    SkillConfigProperties skillConfig) {
        this.toolRegistry = toolRegistry;
        this.validationConfig = skillConfig.getValidation();
    }

    /** 由 SkillAutoConfiguration 在 ApplicationReadyEvent 后调用，切换"工具未注册"提示为 WARN。 */
    public void markStartupComplete() {
        this.startupComplete = true;
    }

    /**
     * 校验 Skill 定义的合法性。
     *
     * @param definition 待校验的 Skill 定义
     * @return 校验结果，包含是否合法和错误信息列表
     */
    public ValidationResult validate(SkillDefinition definition) {
        var errors = new ArrayList<String>();

        // 校验 ID 格式
        if (definition.id() == null || !ID_PATTERN.matcher(definition.id()).matches()) {
            errors.add("Skill ID 格式不合法，必须匹配 ^[a-z0-9.-]{1,64}$: " + definition.id());
        }

        // 校验名称长度
        if (definition.name() != null && definition.name().length() > validationConfig.getMaxNameLength()) {
            errors.add("Skill 名称长度超过限制: " + definition.name().length() + " > " + validationConfig.getMaxNameLength());
        }

        // 校验 Instructions 长度
        if (definition.instructions() != null && definition.instructions().length() > validationConfig.getMaxInstructionsLength()) {
            errors.add("Instructions 长度超过限制: " + definition.instructions().length() + " > " + validationConfig.getMaxInstructionsLength());
        }

        // suggestedTools 中的工具 ID 可能在后续启动阶段才注册（如 BuiltinTool 在 ApplicationReadyEvent 注册）。
        // 启动期完全 silent 避免假警告噪音；启动完成后才 WARN（此时工具已全部注册，缺失说明是真错）。
        if (startupComplete && definition.suggestedTools() != null) {
            for (String toolId : definition.suggestedTools()) {
                if (toolRegistry.resolve(toolId).isEmpty()) {
                    log.warn("Skill '{}' 的建议工具 '{}' 未注册", definition.id(), toolId);
                }
            }
        }

        boolean valid = errors.isEmpty();
        if (!valid) {
            log.debug("Skill 定义校验失败: id={}, errors={}", definition.id(), errors);
        }
        return new ValidationResult(valid, errors);
    }

    /**
     * 校验结果 — 包含是否合法和错误信息列表。
     *
     * @param valid  是否校验通过
     * @param errors 错误信息列表（不可变）
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        /** 紧凑构造器 — 防御性拷贝。 */
        public ValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
