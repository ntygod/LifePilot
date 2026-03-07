package com.lifepilot.skill.validation;

import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.validation.SandboxValidator.SandboxValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxValidator} 单元测试 — 验证 SKILL.md 沙箱校验。
 *
 * @author zsg
 * @since 2026-02-25
 */
class SandboxValidatorTest {

    private SandboxValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SandboxValidator(new MarkdownSkillParser());
    }

    private String validSkillMd() {
        return """
                ---
                id: my-skill-1
                name: "测试技能"
                description: "这是一个测试技能"
                suggested-tools:
                  - tool-a
                  - tool-b
                ---

                你是一个测试助手
                """;
    }

    // ── 合法 SKILL.md ──

    @Test
    void 合法SKILL_MD_沙箱验证通过() {
        SandboxValidationResult result = validator.validate(validSkillMd());

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ── instructions 长度校验 ──

    @Test
    void instructions超过5000字符_沙箱验证失败() {
        String longInstructions = "x".repeat(5001);
        String md = """
                ---
                id: long-instructions-skill
                name: "长指令技能"
                description: "测试超长 instructions"
                suggested-tools:
                  - tool-a
                ---

                %s
                """.formatted(longInstructions);

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("instructions") && e.contains("5000"));
    }

    // ── suggestedTools 数量校验 ──

    @Test
    void suggestedTools超过10个_沙箱验证失败() {
        StringBuilder tools = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            tools.append("  - tool-").append(i).append("\n");
        }
        String md = """
                ---
                id: many-tools-skill
                name: "多工具技能"
                description: "测试超多工具"
                suggested-tools:
                %s---

                你是一个助手
                """.formatted(tools.toString());

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("suggestedTools") && e.contains("10"));
    }

    // ── 两个限制同时超出 ──

    @Test
    void instructions和suggestedTools同时超限_返回多个错误() {
        String longInstructions = "x".repeat(5001);
        StringBuilder tools = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            tools.append("  - tool-").append(i).append("\n");
        }
        String md = """
                ---
                id: both-exceed-skill
                name: "双超限技能"
                description: "测试两个限制同时超出"
                suggested-tools:
                %s---

                %s
                """.formatted(tools.toString(), longInstructions);

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).anyMatch(e -> e.contains("instructions"));
        assertThat(result.errors()).anyMatch(e -> e.contains("suggestedTools"));
    }

    // ── 解析失败 ──

    @Test
    void 缺少Frontmatter_沙箱验证失败() {
        String noFrontmatter = "你是一个测试助手";

        SandboxValidationResult result = validator.validate(noFrontmatter);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── 边界值 ──

    @Test
    void instructions恰好5000字符_沙箱验证通过() {
        String exactInstructions = "x".repeat(5000);
        String md = """
                ---
                id: exact-instructions-skill
                name: "精确长度技能"
                description: "测试恰好 5000 字符"
                suggested-tools:
                  - tool-a
                ---

                %s
                """.formatted(exactInstructions);

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void suggestedTools恰好10个_沙箱验证通过() {
        StringBuilder tools = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            tools.append("  - tool-").append(i).append("\n");
        }
        String md = """
                ---
                id: exact-tools-skill
                name: "精确工具数技能"
                description: "测试恰好 10 个工具"
                suggested-tools:
                %s---

                你是一个助手
                """.formatted(tools.toString());

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }
}
