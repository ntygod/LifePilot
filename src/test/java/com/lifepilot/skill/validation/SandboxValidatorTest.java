package com.lifepilot.skill.validation;

import com.lifepilot.skill.validation.SandboxValidator.SandboxValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxValidator} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class SandboxValidatorTest {

    private SandboxValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SandboxValidator();
    }

    /** 构建合法的 YAML 字符串（在沙箱限制内）。 */
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
    //  合法 YAML — 沙箱验证通过
    // ─────────────────────────────────────────────

    @Test
    void 合法YAML_沙箱验证通过() {
        SandboxValidationResult result = validator.validate(validYaml());

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  system-prompt 长度校验
    // ─────────────────────────────────────────────

    @Test
    void systemPrompt超过5000字符_沙箱验证失败() {
        String longPrompt = "x".repeat(5001);
        String yaml = """
                skill:
                  id: long-prompt-skill
                  name: 长提示词技能
                  description: 测试超长 system-prompt
                  system-prompt: "%s"
                  allowed-tools:
                    - tool-a
                """.formatted(longPrompt);

        SandboxValidationResult result = validator.validate(yaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("system-prompt") && e.contains("5000"));
    }

    // ─────────────────────────────────────────────
    //  allowed-tools 数量校验
    // ─────────────────────────────────────────────

    @Test
    void allowedTools超过10个_沙箱验证失败() {
        StringBuilder toolsYaml = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            toolsYaml.append("    - tool-").append(i).append("\n");
        }
        String yaml = """
                skill:
                  id: many-tools-skill
                  name: 多工具技能
                  description: 测试超多工具
                  system-prompt: 你是一个助手
                  allowed-tools:
                %s""".formatted(toolsYaml.toString());

        SandboxValidationResult result = validator.validate(yaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("allowed-tools") && e.contains("10"));
    }

    // ─────────────────────────────────────────────
    //  两个限制同时超出
    // ─────────────────────────────────────────────

    @Test
    void systemPrompt和allowedTools同时超限_返回多个错误() {
        String longPrompt = "x".repeat(5001);
        StringBuilder toolsYaml = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            toolsYaml.append("    - tool-").append(i).append("\n");
        }
        String yaml = """
                skill:
                  id: both-exceed-skill
                  name: 双超限技能
                  description: 测试两个限制同时超出
                  system-prompt: "%s"
                  allowed-tools:
                %s""".formatted(longPrompt, toolsYaml.toString());

        SandboxValidationResult result = validator.validate(yaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).anyMatch(e -> e.contains("system-prompt"));
        assertThat(result.errors()).anyMatch(e -> e.contains("allowed-tools"));
    }

    // ─────────────────────────────────────────────
    //  无法解析为 SkillDefinition
    // ─────────────────────────────────────────────

    @Test
    void 非法YAML语法_沙箱验证失败() {
        String invalidYaml = """
                skill:
                  id: [broken
                """;

        SandboxValidationResult result = validator.validate(invalidYaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void 缺少skill根节点_沙箱验证失败() {
        String noSkillKey = """
                other:
                  id: my-skill
                  name: 测试
                """;

        SandboxValidationResult result = validator.validate(noSkillKey);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("skill"));
    }

    // ─────────────────────────────────────────────
    //  边界值 — 恰好在限制内
    // ─────────────────────────────────────────────

    @Test
    void systemPrompt恰好5000字符_沙箱验证通过() {
        String exactPrompt = "x".repeat(5000);
        String yaml = """
                skill:
                  id: exact-prompt-skill
                  name: 精确长度技能
                  description: 测试恰好 5000 字符
                  system-prompt: "%s"
                  allowed-tools:
                    - tool-a
                """.formatted(exactPrompt);

        SandboxValidationResult result = validator.validate(yaml);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void allowedTools恰好10个_沙箱验证通过() {
        StringBuilder toolsYaml = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            toolsYaml.append("    - tool-").append(i).append("\n");
        }
        String yaml = """
                skill:
                  id: exact-tools-skill
                  name: 精确工具数技能
                  description: 测试恰好 10 个工具
                  system-prompt: 你是一个助手
                  allowed-tools:
                %s""".formatted(toolsYaml.toString());

        SandboxValidationResult result = validator.validate(yaml);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  非 Map 类型 YAML
    // ─────────────────────────────────────────────

    @Test
    void 非Map类型YAML_沙箱验证失败() {
        String scalarYaml = "just a string";

        SandboxValidationResult result = validator.validate(scalarYaml);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Map"));
    }
}
