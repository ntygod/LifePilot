package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * YAML Schema 校验器 — 校验 YAML Skill 定义的结构合规性。
 *
 * <p>校验规则：
 * <ul>
 *   <li>根节点包含 "skill" 键</li>
 *   <li>必填字段：id、name、description、system-prompt、allowed-tools</li>
 *   <li>id 格式：{@code ^[a-z0-9-]{1,64}$}</li>
 *   <li>allowed-tools 为非空列表</li>
 *   <li>execution.max-steps ≤ 配置上限（默认 50）</li>
 *   <li>execution.timeout-seconds ≤ 配置上限（默认 600）</li>
 *   <li>version 符合语义化版本格式（MAJOR.MINOR.PATCH）</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class YamlSchemaValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    /** 必填字段列表。 */
    private static final List<String> REQUIRED_FIELDS = List.of(
            "id", "name", "description", "system-prompt", "allowed-tools"
    );

    private final SkillConfigProperties config;

    public YamlSchemaValidator(SkillConfigProperties config) {
        this.config = config;
    }

    /**
     * 校验 YAML 解析后的 Map 结构。
     *
     * @param yamlMap YAML 解析后的 Map
     * @return 校验结果
     */
    public ValidationResult validate(Map<String, Object> yamlMap) {
        var errors = new ArrayList<String>();

        // 1. 校验根节点包含 "skill" 键
        if (yamlMap == null || !yamlMap.containsKey("skill")) {
            errors.add("根节点缺少 \"skill\" 键");
            return new ValidationResult(false, errors);
        }

        var skillNode = yamlMap.get("skill");
        if (!(skillNode instanceof Map<?, ?> skillMap)) {
            errors.add("\"skill\" 节点必须是 Map 类型");
            return new ValidationResult(false, errors);
        }

        // 2. 校验必填字段存在且非空
        for (String field : REQUIRED_FIELDS) {
            var value = skillMap.get(field);
            if (value == null) {
                errors.add("缺少必填字段: " + field);
            } else if (value instanceof String s && s.isBlank()) {
                errors.add("必填字段不能为空: " + field);
            }
        }

        // 3. 校验 id 格式
        var idValue = skillMap.get("id");
        if (idValue instanceof String id && !id.isBlank()) {
            if (!ID_PATTERN.matcher(id).matches()) {
                errors.add("id 格式不合法，必须匹配 ^[a-z0-9-]{1,64}$: " + id);
            }
        }

        // 4. 校验 allowed-tools 为非空列表
        var allowedTools = skillMap.get("allowed-tools");
        if (allowedTools != null) {
            if (!(allowedTools instanceof List<?> toolList)) {
                errors.add("allowed-tools 必须是列表类型");
            } else if (toolList.isEmpty()) {
                errors.add("allowed-tools 不能为空列表");
            }
        }

        // 5. 校验 execution 上限
        var executionNode = skillMap.get("execution");
        if (executionNode instanceof Map<?, ?> executionMap) {
            var validation = config.getValidation();

            var maxStepsValue = executionMap.get("max-steps");
            if (maxStepsValue instanceof Number maxSteps) {
                if (maxSteps.intValue() > validation.getSchemaMaxSteps()) {
                    errors.add("execution.max-steps 超过上限 " + validation.getSchemaMaxSteps()
                            + ": " + maxSteps.intValue());
                }
            }

            var timeoutValue = executionMap.get("timeout-seconds");
            if (timeoutValue instanceof Number timeout) {
                if (timeout.intValue() > validation.getSchemaMaxTimeoutSeconds()) {
                    errors.add("execution.timeout-seconds 超过上限 " + validation.getSchemaMaxTimeoutSeconds()
                            + ": " + timeout.intValue());
                }
            }
        }

        // 6. 校验 version 语义化版本格式
        var versionValue = skillMap.get("version");
        if (versionValue instanceof String version && !version.isBlank()) {
            if (!VERSION_PATTERN.matcher(version).matches()) {
                errors.add("version 格式不合法，必须符合语义化版本 MAJOR.MINOR.PATCH: " + version);
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * YAML Schema 校验结果。
     *
     * @param valid  是否校验通过
     * @param errors 错误信息列表
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        public ValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
