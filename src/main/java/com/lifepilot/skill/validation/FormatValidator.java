package com.lifepilot.skill.validation;

import com.lifepilot.skill.yaml.YamlSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.List;
import java.util.Map;

/**
 * 格式验证器 — 校验 YAML 语法和 Schema 结构。
 *
 * <p>复用 {@link YamlSchemaValidator} 的校验逻辑，额外处理 YAML 语法解析错误。
 * 验证流程：
 * <ol>
 *   <li>校验输入非空</li>
 *   <li>使用 SnakeYAML 解析 YAML 语法</li>
 *   <li>调用 YamlSchemaValidator 校验 Schema 结构</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FormatValidator {

    private static final Logger log = LoggerFactory.getLogger(FormatValidator.class);

    private final YamlSchemaValidator schemaValidator;

    public FormatValidator(YamlSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    /**
     * 校验 YAML 内容的格式。
     *
     * <p>先校验 YAML 语法正确性，再委托 {@link YamlSchemaValidator} 校验 Schema 结构。</p>
     *
     * @param yamlContent YAML 字符串
     * @return 校验结果，包含解析后的 Map（成功时）
     */
    public FormatValidationResult validate(String yamlContent) {
        // 1. 校验输入非空
        if (yamlContent == null || yamlContent.isBlank()) {
            log.debug("格式验证失败: YAML 内容为空");
            return new FormatValidationResult(false, List.of("YAML 内容不能为空"), null);
        }

        // 2. 使用 SnakeYAML 解析 YAML 语法
        Map<String, Object> parsedMap;
        try {
            var yaml = new Yaml();
            Object parsed = yaml.load(yamlContent);
            if (!(parsed instanceof Map<?, ?> rawMap)) {
                log.debug("格式验证失败: YAML 解析结果不是 Map 类型");
                return new FormatValidationResult(false, List.of("YAML 解析结果必须是 Map 类型"), null);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) rawMap;
            parsedMap = typedMap;
        } catch (YAMLException e) {
            log.debug("格式验证失败: YAML 语法错误: {}", e.getMessage());
            return new FormatValidationResult(false, List.of("YAML 语法错误: " + e.getMessage()), null);
        }

        // 3. 调用 YamlSchemaValidator 校验 Schema 结构
        var schemaResult = schemaValidator.validate(parsedMap);
        if (!schemaResult.valid()) {
            log.debug("格式验证失败: Schema 校验错误数={}", schemaResult.errors().size());
            return new FormatValidationResult(false, schemaResult.errors(), null);
        }

        log.debug("格式验证通过");
        return new FormatValidationResult(true, List.of(), parsedMap);
    }

    /**
     * 格式验证结果。
     *
     * @param passed    是否通过
     * @param errors    错误信息列表
     * @param parsedMap 解析后的 Map（通过时非 null）
     */
    public record FormatValidationResult(
            boolean passed,
            List<String> errors,
            @Nullable Map<String, Object> parsedMap
    ) {
        /** 防御性拷贝。 */
        public FormatValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
