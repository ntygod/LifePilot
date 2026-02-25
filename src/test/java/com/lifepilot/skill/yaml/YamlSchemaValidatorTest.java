package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.yaml.YamlSchemaValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link YamlSchemaValidator} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class YamlSchemaValidatorTest {

    private YamlSchemaValidator validator;

    @BeforeEach
    void setUp() {
        validator = new YamlSchemaValidator(new SkillConfigProperties());
    }

    /** 构建合法的 YAML Map。 */
    private Map<String, Object> validYamlMap() {
        var skill = new HashMap<String, Object>();
        skill.put("id", "my-skill-1");
        skill.put("name", "测试技能");
        skill.put("description", "这是一个测试技能");
        skill.put("system-prompt", "你是一个测试助手");
        skill.put("allowed-tools", List.of("tool-a", "tool-b"));
        return new HashMap<>(Map.of("skill", skill));
    }

    // ─────────────────────────────────────────────
    //  合法定义校验
    // ─────────────────────────────────────────────

    @Test
    void 合法YAML_校验通过() {
        ValidationResult result = validator.validate(validYamlMap());
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void 包含所有可选字段_校验通过() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("version", "1.2.3");
        skill.put("execution", Map.of("max-steps", 10, "timeout-seconds", 300));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  根节点 "skill" 键校验
    // ─────────────────────────────────────────────

    @Test
    void 缺少skill根节点_校验失败() {
        ValidationResult result = validator.validate(Map.of("other", "value"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("根节点缺少 \"skill\" 键"));
    }

    @Test
    void null输入_校验失败() {
        ValidationResult result = validator.validate(null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("根节点缺少 \"skill\" 键"));
    }

    @Test
    void skill节点非Map类型_校验失败() {
        ValidationResult result = validator.validate(Map.of("skill", "not-a-map"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("\"skill\" 节点必须是 Map 类型"));
    }

    // ─────────────────────────────────────────────
    //  必填字段校验
    // ─────────────────────────────────────────────

    @Test
    void 缺少id_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.remove("id");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少必填字段: id"));
    }

    @Test
    void 缺少name_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.remove("name");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少必填字段: name"));
    }

    @Test
    void 缺少description_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.remove("description");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少必填字段: description"));
    }

    @Test
    void 缺少systemPrompt_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.remove("system-prompt");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少必填字段: system-prompt"));
    }

    @Test
    void 缺少allowedTools_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.remove("allowed-tools");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少必填字段: allowed-tools"));
    }

    @Test
    void 必填字段为空字符串_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("name", "   ");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("必填字段不能为空: name"));
    }

    // ─────────────────────────────────────────────
    //  ID 格式校验
    // ─────────────────────────────────────────────

    @Test
    void ID含大写字母_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("id", "My-Skill");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("id 格式不合法"));
    }

    @Test
    void ID含下划线_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("id", "my_skill");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("id 格式不合法"));
    }

    @Test
    void ID超过64字符_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("id", "a".repeat(65));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("id 格式不合法"));
    }

    @Test
    void ID含连字符和数字_校验通过() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("id", "my-skill-123");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void ID恰好64字符_校验通过() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("id", "a".repeat(64));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  allowed-tools 校验
    // ─────────────────────────────────────────────

    @Test
    void allowedTools为空列表_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("allowed-tools", List.of());

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("allowed-tools 不能为空列表"));
    }

    @Test
    void allowedTools非列表类型_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("allowed-tools", "not-a-list");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("allowed-tools 必须是列表类型"));
    }

    // ─────────────────────────────────────────────
    //  execution 上限校验
    // ─────────────────────────────────────────────

    @Test
    void maxSteps超过上限_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("execution", Map.of("max-steps", 51));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("execution.max-steps 超过上限 50"));
    }

    @Test
    void maxSteps恰好等于上限_校验通过() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("execution", Map.of("max-steps", 50));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void timeoutSeconds超过上限_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("execution", Map.of("timeout-seconds", 601));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("execution.timeout-seconds 超过上限 600"));
    }

    @Test
    void timeoutSeconds恰好等于上限_校验通过() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("execution", Map.of("timeout-seconds", 600));

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void execution不存在_校验通过() {
        // execution 是可选字段，不存在时不校验
        var yamlMap = validYamlMap();
        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void 自定义配置上限_校验使用配置值() {
        var config = new SkillConfigProperties();
        config.getValidation().setSchemaMaxSteps(20);
        config.getValidation().setSchemaMaxTimeoutSeconds(120);
        var customValidator = new YamlSchemaValidator(config);

        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("execution", Map.of("max-steps", 21, "timeout-seconds", 121));

        ValidationResult result = customValidator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("execution.max-steps 超过上限 20"));
        assertThat(result.errors()).anyMatch(e -> e.contains("execution.timeout-seconds 超过上限 120"));
    }

    // ─────────────────────────────────────────────
    //  version 格式校验
    // ─────────────────────────────────────────────

    @Test
    void 合法版本号_校验通过() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("version", "1.0.0");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void 版本号缺少PATCH_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("version", "1.0");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("version 格式不合法"));
    }

    @Test
    void 版本号含前缀v_校验失败() {
        var yamlMap = validYamlMap();
        @SuppressWarnings("unchecked")
        var skill = (Map<String, Object>) yamlMap.get("skill");
        skill.put("version", "v1.0.0");

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("version 格式不合法"));
    }

    @Test
    void 无version字段_校验通过() {
        // version 是可选字段
        var yamlMap = validYamlMap();
        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  多错误累积
    // ─────────────────────────────────────────────

    @Test
    void 多个校验错误_全部返回() {
        var skill = new HashMap<String, Object>();
        skill.put("id", "INVALID!");
        skill.put("name", "");
        skill.put("description", "ok");
        skill.put("system-prompt", "ok");
        skill.put("allowed-tools", List.of());
        skill.put("version", "bad");
        var yamlMap = Map.<String, Object>of("skill", skill);

        ValidationResult result = validator.validate(yamlMap);
        assertThat(result.valid()).isFalse();
        // 空 name + 非法 id + 空 allowed-tools + 非法 version = 4 个错误
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(4);
    }

    // ─────────────────────────────────────────────
    //  ValidationResult 防御性拷贝
    // ─────────────────────────────────────────────

    @Test
    void ValidationResult_errors不可变() {
        var errors = new java.util.ArrayList<>(List.of("错误1"));
        var result = new ValidationResult(false, errors);
        errors.add("错误2");
        assertThat(result.errors()).hasSize(1);
    }
}
