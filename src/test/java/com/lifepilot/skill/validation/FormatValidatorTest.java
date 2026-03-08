package com.lifepilot.skill.validation;

import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.validation.FormatValidator.FormatValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FormatValidator} 单元测试 — 验证 SKILL.md 格式校验。
 *
 * @author zsg
 * @since 2026-02-25
 */
class FormatValidatorTest {

    private FormatValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FormatValidator(new MarkdownSkillParser());
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

    // ── 合法 SKILL.md 校验 ──

    @Test
    void 合法SKILL_MD_格式验证通过() {
        FormatValidationResult result = validator.validate(validSkillMd());

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.parsedMap()).isNotNull();
    }

    @Test
    void 合法SKILL_MD_parsedMap包含skill键() {
        FormatValidationResult result = validator.validate(validSkillMd());

        assertThat(result.passed()).isTrue();
        assertThat(result.parsedMap()).containsKey("skill");
    }

    // ── 缺少 Frontmatter 分隔符 ──

    @Test
    void 缺少Frontmatter分隔符_返回错误() {
        String noFrontmatter = "你是一个测试助手";

        FormatValidationResult result = validator.validate(noFrontmatter);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── 缺少必填字段 ──

    @Test
    void 缺少必填字段_校验失败() {
        String missingFields = """
                ---
                id: my-skill
                ---

                你是一个测试助手
                """;

        FormatValidationResult result = validator.validate(missingFields);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── 空 Body（instructions 为空）──

    @Test
    void 空Body_校验失败() {
        String emptyBody = """
                ---
                id: my-skill
                name: "测试"
                description: "测试"
                ---
                """;

        FormatValidationResult result = validator.validate(emptyBody);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── null / blank 输入 ──

    @Test
    void null输入_返回错误() {
        FormatValidationResult result = validator.validate(null);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("不能为空"));
    }

    @Test
    void 空字符串输入_返回错误() {
        FormatValidationResult result = validator.validate("");

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("不能为空"));
    }

    @Test
    void 空白字符串输入_返回错误() {
        FormatValidationResult result = validator.validate("   \n  \t  ");

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("不能为空"));
    }

    // ── 包含可选字段 ──

    @Test
    void 包含version和suggestedTools_格式验证通过() {
        String withOptional = """
                ---
                id: my-skill-2
                name: "高级技能"
                description: "包含可选字段的技能"
                version: "1.2.3"
                suggested-tools:
                  - tool-a
                ---

                你是一个高级助手
                """;

        FormatValidationResult result = validator.validate(withOptional);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.parsedMap()).isNotNull();
    }
}
