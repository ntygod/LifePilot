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
                name: "\u6D4B\u8BD5\u6280\u80FD"
                description: "\u8FD9\u662F\u4E00\u4E2A\u6D4B\u8BD5\u6280\u80FD"
                allowed-tools:
                  - tool-a
                  - tool-b
                ---

                \u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B
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
        String noFrontmatter = "\u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B";

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

                \u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B
                """;

        FormatValidationResult result = validator.validate(missingFields);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── 空 Body（System Prompt 为空）──

    @Test
    void 空Body_校验失败() {
        String emptyBody = """
                ---
                id: my-skill
                name: "\u6D4B\u8BD5"
                description: "\u6D4B\u8BD5"
                ---
                """;

        FormatValidationResult result = validator.validate(emptyBody);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("System Prompt"));
    }

    // ── null / blank 输入 ──

    @Test
    void null输入_返回错误() {
        FormatValidationResult result = validator.validate(null);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("\u4E0D\u80FD\u4E3A\u7A7A"));
    }

    @Test
    void 空字符串输入_返回错误() {
        FormatValidationResult result = validator.validate("");

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("\u4E0D\u80FD\u4E3A\u7A7A"));
    }

    @Test
    void 空白字符串输入_返回错误() {
        FormatValidationResult result = validator.validate("   \n  \t  ");

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("\u4E0D\u80FD\u4E3A\u7A7A"));
    }

    // ── 包含可选字段 ──

    @Test
    void 包含version和execution_格式验证通过() {
        String withOptional = """
                ---
                id: my-skill-2
                name: "\u9AD8\u7EA7\u6280\u80FD"
                description: "\u5305\u542B\u53EF\u9009\u5B57\u6BB5\u7684\u6280\u80FD"
                version: "1.2.3"
                allowed-tools:
                  - tool-a
                execution:
                  max-steps: 20
                  timeout-seconds: 300
                ---

                \u4F60\u662F\u4E00\u4E2A\u9AD8\u7EA7\u52A9\u624B
                """;

        FormatValidationResult result = validator.validate(withOptional);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.parsedMap()).isNotNull();
    }
}
