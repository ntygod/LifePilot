package com.lifepilot.skill;

import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.validation.FormatValidator;
import net.jqwik.api.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 7: 验证管线对有效/无效输入的判定 属性测试。
 *
 * <p>验证：对任意有效的 SkillDefinition（必填字段非空），FormatValidator 应通过验证；
 * 对任意缺少必填字段（id/name/instructions 为空或 blank）的输入，FormatValidator 应拒绝。</p>
 *
 * <p><b>Validates: Requirements AC-7.3</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class FormatValidator属性测试 {

    private final MarkdownSkillSerializer serializer = new MarkdownSkillSerializer();
    private final FormatValidator formatValidator = new FormatValidator(new MarkdownSkillParser());

    // ── Property 7a: 有效输入通过验证 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 7a: 有效 SkillDefinition 通过 FormatValidator")
    void 有效SkillDefinition序列化后_FormatValidator通过(
            @ForAll("validSkillDefinitions") SkillDefinition definition) {

        String markdown = serializer.serialize(definition);
        var result = formatValidator.validate(markdown);

        // **Validates: AC-7.3** — 有效输入应通过
        assertThat(result.passed())
                .as("有效 SKILL.md 应通过格式验证，id=%s", definition.id())
                .isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ── Property 7b: 无效输入被拒绝 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 7b: 缺少必填字段的输入被 FormatValidator 拒绝")
    void 缺少必填字段的SKILL_md_FormatValidator拒绝(
            @ForAll("invalidMarkdownContents") String invalidMarkdown) {

        var result = formatValidator.validate(invalidMarkdown);

        // **Validates: AC-7.3** — 无效输入应被拒绝
        assertThat(result.passed())
                .as("缺少必填字段的 SKILL.md 应被拒绝")
                .isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ── 生成器 ──

    @Provide
    Arbitrary<SkillDefinition> validSkillDefinitions() {
        var idArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('-')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.isBlank() && s.matches("^[a-z0-9-]+$"));

        var nameArb = safeString(1, 30);
        var descArb = safeString(1, 50);
        var instructionsArb = safeString(1, 100)
                .filter(s -> !s.contains("---"));

        var toolArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-')
                .ofMinLength(1).ofMaxLength(15)
                .filter(s -> !s.isBlank());
        var toolsArb = toolArb.list().ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(idArb, nameArb, descArb, instructionsArb, toolsArb)
                .as((id, name, desc, instructions, tools) ->
                        SkillDefinition.builder()
                                .id(id)
                                .name(name)
                                .description(desc)
                                .version("1.0.0")
                                .source(new SkillSource.UserDefined("", null))
                                .instructions(instructions)
                                .suggestedTools(tools)
                                .metadata(Map.of())
                                .build()
                );
    }

    @Provide
    Arbitrary<String> invalidMarkdownContents() {
        // 生成缺少不同必填字段的 SKILL.md 内容
        return Arbitraries.of(
                // 缺少 id
                "---\nname: test\ndescription: desc\n---\n\nSome instructions",
                // 缺少 name
                "---\nid: test\ndescription: desc\n---\n\nSome instructions",
                // 缺少 description
                "---\nid: test\nname: test\n---\n\nSome instructions",
                // 缺少 instructions（空 body）
                "---\nid: test\nname: test\ndescription: desc\n---\n",
                // id 为空字符串
                "---\nid: ''\nname: test\ndescription: desc\n---\n\nSome instructions",
                // name 为空字符串
                "---\nid: test\nname: ''\ndescription: desc\n---\n\nSome instructions",
                // 完全空内容
                "",
                // 只有空白
                "   \n  \n  ",
                // 缺少 frontmatter
                "Just some text without frontmatter",
                // 只有一个 ---
                "---\nid: test\nname: test\ndescription: desc"
        );
    }

    private Arbitrary<String> safeString(int minLen, int maxLen) {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '-', '_')
                .ofMinLength(minLen).ofMaxLength(maxLen)
                .filter(s -> !s.isBlank());
    }
}
