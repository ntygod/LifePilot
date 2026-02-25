package com.lifepilot.skill.validation;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.validation.FormatValidator.FormatValidationResult;
import com.lifepilot.skill.yaml.YamlSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FormatValidator} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class FormatValidatorTest {

    private FormatValidator validator;

    @BeforeEach
    void setUp() {
        var schemaValidator = new YamlSchemaValidator(new SkillConfigProperties());
        validator = new FormatValidator(schemaValidator);
    }

    /** 构建合法的 YAML 字符串。 */
    private String validYaml() {
        return """
                skill:
                  id: my-skill-1
                  name: 测试技能
                  description: 这是一个测试技能
                  system-prompt: 你是一个测试助手
                  allowed-tools:
                    - tool-a
                    - tool-b
                """;
    }

    // ─────────────────────────────────────────────
    //  合法 YAML 校验
    // ─────────────────────────────────────────────

    @Test
    void 合法YAML_格式验证通过() {
        FormatValidationResult result = validator.validate(validYaml());

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.parsedMap()).isNotNull();
    }

    @Test
    void 合法YAML_parsedMap包含skill键() {
        FormatValidationResult result = validator.validate(validYaml());

        assertThat(result.passed()).isTrue();
        assertThat(result.parsedMap()).containsKey("skill");
    }

    // ─────────────────────────────────────────────
    //  YAML 语法错误
    // ─────────────────────────────────────────────

    @Test
    void 非法YAML语法_返回语法错误() {
        String invalidYaml = """
                skill:
                  id: my-skill
                  name: [invalid
                """;

        FormatValidationResult result = validator.validate(invalidYaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("YAML 语法错误"));
        assertThat(result.parsedMap()).isNull();
    }

    @Test
    void 非Map类型YAML_返回类型错误() {
        String scalarYaml = "just a string";

        FormatValidationResult result = validator.validate(scalarYaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Map 类型"));
        assertThat(result.parsedMap()).isNull();
    }

    // ─────────────────────────────────────────────
    //  Schema 校验失败
    // ─────────────────────────────────────────────

    @Test
    void 缺少skill根节点_Schema校验失败() {
        String noSkillKey = """
                other:
                  id: my-skill
                """;

        FormatValidationResult result = validator.validate(noSkillKey);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("skill"));
        assertThat(result.parsedMap()).isNull();
    }

    @Test
    void 缺少必填字段_Schema校验失败() {
        String missingFields = """
                skill:
                  id: my-skill
                """;

        FormatValidationResult result = validator.validate(missingFields);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.parsedMap()).isNull();
    }

    @Test
    void id格式不合法_Schema校验失败() {
        String invalidId = """
                skill:
                  id: INVALID_ID!
                  name: 测试技能
                  description: 这是一个测试技能
                  system-prompt: 你是一个测试助手
                  allowed-tools:
                    - tool-a
                """;

        FormatValidationResult result = validator.validate(invalidId);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("id"));
    }

    // ─────────────────────────────────────────────
    //  null / blank 输入
    // ─────────────────────────────────────────────

    @Test
    void null输入_返回错误() {
        FormatValidationResult result = validator.validate(null);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("不能为空"));
        assertThat(result.parsedMap()).isNull();
    }

    @Test
    void 空字符串输入_返回错误() {
        FormatValidationResult result = validator.validate("");

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("不能为空"));
        assertThat(result.parsedMap()).isNull();
    }

    @Test
    void 空白字符串输入_返回错误() {
        FormatValidationResult result = validator.validate("   \n  \t  ");

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("不能为空"));
        assertThat(result.parsedMap()).isNull();
    }

    // ─────────────────────────────────────────────
    //  包含可选字段的合法 YAML
    // ─────────────────────────────────────────────

    @Test
    void 包含version和execution_格式验证通过() {
        String yamlWithOptional = """
                skill:
                  id: my-skill-2
                  name: 高级技能
                  description: 包含可选字段的技能
                  system-prompt: 你是一个高级助手
                  allowed-tools:
                    - tool-a
                  version: "1.2.3"
                  execution:
                    max-steps: 20
                    timeout-seconds: 300
                """;

        FormatValidationResult result = validator.validate(yamlWithOptional);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.parsedMap()).isNotNull();
    }
}
