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
                name: "\u6D4B\u8BD5\u6280\u80FD"
                description: "\u8FD9\u662F\u4E00\u4E2A\u6D4B\u8BD5\u6280\u80FD"
                allowed-tools:
                  - tool-a
                  - tool-b
                ---

                \u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B
                """;
    }

    // ── 合法 SKILL.md ──

    @Test
    void 合法SKILL_MD_沙箱验证通过() {
        SandboxValidationResult result = validator.validate(validSkillMd());

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ── systemPrompt 长度校验 ──

    @Test
    void systemPrompt超过5000字符_沙箱验证失败() {
        String longPrompt = "x".repeat(5001);
        String md = """
                ---
                id: long-prompt-skill
                name: "\u957F\u63D0\u793A\u8BCD\u6280\u80FD"
                description: "\u6D4B\u8BD5\u8D85\u957F system-prompt"
                allowed-tools:
                  - tool-a
                ---

                %s
                """.formatted(longPrompt);

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("systemPrompt") && e.contains("5000"));
    }

    // ── allowedTools 数量校验 ──

    @Test
    void allowedTools超过10个_沙箱验证失败() {
        StringBuilder tools = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            tools.append("  - tool-").append(i).append("\n");
        }
        String md = """
                ---
                id: many-tools-skill
                name: "\u591A\u5DE5\u5177\u6280\u80FD"
                description: "\u6D4B\u8BD5\u8D85\u591A\u5DE5\u5177"
                allowed-tools:
                %s---

                \u4F60\u662F\u4E00\u4E2A\u52A9\u624B
                """.formatted(tools.toString());

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("allowedTools") && e.contains("10"));
    }

    // ── 两个限制同时超出 ──

    @Test
    void systemPrompt和allowedTools同时超限_返回多个错误() {
        String longPrompt = "x".repeat(5001);
        StringBuilder tools = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            tools.append("  - tool-").append(i).append("\n");
        }
        String md = """
                ---
                id: both-exceed-skill
                name: "\u53CC\u8D85\u9650\u6280\u80FD"
                description: "\u6D4B\u8BD5\u4E24\u4E2A\u9650\u5236\u540C\u65F6\u8D85\u51FA"
                allowed-tools:
                %s---

                %s
                """.formatted(tools.toString(), longPrompt);

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).anyMatch(e -> e.contains("systemPrompt"));
        assertThat(result.errors()).anyMatch(e -> e.contains("allowedTools"));
    }

    // ── 解析失败 ──

    @Test
    void 缺少Frontmatter_沙箱验证失败() {
        String noFrontmatter = "\u4F60\u662F\u4E00\u4E2A\u6D4B\u8BD5\u52A9\u624B";

        SandboxValidationResult result = validator.validate(noFrontmatter);

        assertThat(result.passed()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── 边界值 ──

    @Test
    void systemPrompt恰好5000字符_沙箱验证通过() {
        String exactPrompt = "x".repeat(5000);
        String md = """
                ---
                id: exact-prompt-skill
                name: "\u7CBE\u786E\u957F\u5EA6\u6280\u80FD"
                description: "\u6D4B\u8BD5\u6070\u597D 5000 \u5B57\u7B26"
                allowed-tools:
                  - tool-a
                ---

                %s
                """.formatted(exactPrompt);

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void allowedTools恰好10个_沙箱验证通过() {
        StringBuilder tools = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            tools.append("  - tool-").append(i).append("\n");
        }
        String md = """
                ---
                id: exact-tools-skill
                name: "\u7CBE\u786E\u5DE5\u5177\u6570\u6280\u80FD"
                description: "\u6D4B\u8BD5\u6070\u597D 10 \u4E2A\u5DE5\u5177"
                allowed-tools:
                %s---

                \u4F60\u662F\u4E00\u4E2A\u52A9\u624B
                """.formatted(tools.toString());

        SandboxValidationResult result = validator.validate(md);

        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
    }
}
