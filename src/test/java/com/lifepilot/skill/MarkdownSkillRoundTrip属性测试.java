package com.lifepilot.skill;

import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: SKILL.md 序列化/解析 Round Trip 属性测试。
 *
 * <p>验证：对任意有效 SkillDefinition，序列化为 SKILL.md 后再解析回来，
 * 核心字段（id、name、description、version、instructions、suggestedTools、metadata）应一致。</p>
 *
 * <p><b>Validates: Requirements AC-1.1, AC-1.2, AC-1.3</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class MarkdownSkillRoundTrip属性测试 {

    private final MarkdownSkillSerializer serializer = new MarkdownSkillSerializer();
    private final MarkdownSkillParser parser = new MarkdownSkillParser();

    // ── Property 1: Round Trip ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 1: SKILL.md 序列化/解析 Round Trip")
    void 序列化后解析_核心字段一致(@ForAll("validSkillDefinitions") SkillDefinition original) {
        // 序列化
        String markdown = serializer.serialize(original);

        // 解析
        var parseResult = parser.parse(markdown);

        assertThat(parseResult.success())
                .as("解析应成功，原始 id=%s", original.id())
                .isTrue();

        assertThat(parseResult.definition())
                .as("解析结果不应为 null")
                .isNotNull();

        SkillDefinition parsed = java.util.Objects.requireNonNull(parseResult.definition());

        // 核心字段比较（source 不参与比较，因为 parser 默认使用 UserDefined("", null)）
        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.name()).isEqualTo(original.name());
        assertThat(parsed.description()).isEqualTo(original.description());
        assertThat(parsed.version()).isEqualTo(original.version());
        assertThat(parsed.instructions()).isEqualTo(original.instructions());
        assertThat(parsed.suggestedTools()).isEqualTo(original.suggestedTools());
        assertThat(parsed.metadata()).isEqualTo(original.metadata());
    }

    // ── 生成器 ──

    @Provide
    Arbitrary<SkillDefinition> validSkillDefinitions() {
        // id: 小写字母+数字+连字符，1-64 字符，符合 SkillDefinitionValidator 的 ID_PATTERN
        var idArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('-')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.isBlank() && s.matches("^[a-z0-9-]+$"));

        // name: 非空字符串，避免 YAML 特殊字符
        var nameArb = safeString(1, 50);

        // description: 非空字符串
        var descArb = safeString(1, 100);

        // version: 语义版本号
        var versionArb = Arbitraries.integers().between(0, 9).tuple3()
                .map(t -> t.get1() + "." + t.get2() + "." + t.get3());

        // instructions: 非空 Markdown 内容，避免 YAML frontmatter 分隔符
        var instructionsArb = safeString(1, 200)
                .filter(s -> !s.contains("---"));

        // suggestedTools: 0-5 个工具 ID
        var toolArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.isBlank());
        var toolsArb = toolArb.list().ofMinSize(0).ofMaxSize(5);

        // metadata: 0-3 个键值对（排除 tags/category/author/dependencies 避免 parser 特殊处理冲突）
        var metaKeyArb = Arbitraries.of("custom-key", "env", "priority", "region", "scope");
        var metaValueArb = safeString(1, 30);
        var metadataArb = Arbitraries.maps(metaKeyArb, metaValueArb)
                .ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(idArb, nameArb, descArb, versionArb, instructionsArb, toolsArb, metadataArb)
                .as((id, name, desc, version, instructions, tools, metadata) ->
                        SkillDefinition.builder()
                                .id(id)
                                .name(name)
                                .description(desc)
                                .version(version)
                                .source(new SkillSource.UserDefined("", null))
                                .instructions(instructions)
                                .suggestedTools(tools)
                                .metadata(metadata)
                                .build()
                );
    }

    /**
     * 生成安全字符串 — 避免 YAML 特殊字符和控制字符。
     */
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
