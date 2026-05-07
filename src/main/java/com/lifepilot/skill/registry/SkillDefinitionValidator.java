package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
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
 *   <li>suggestedTools 仅作为元数据保留，不参与注册期硬校验</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillDefinitionValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillDefinitionValidator.class);

    /** Skill ID 合法格式：小写字母、数字、连字符和点号，长度 1-64。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9.-]{1,64}$");

    private final SkillConfigProperties.Validation validationConfig;

    public SkillDefinitionValidator(SkillConfigProperties skillConfig) {
        this.validationConfig = skillConfig.getValidation();
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

        // suggested_tools 已不作为运行时工具加载依赖；仅保留字段供 UI 展示和人工参考。

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
