package com.lifepilot.skill;

import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5: SkillDefinition 字段完整性不变量 属性测试。
 *
 * <p>验证：对任意成功构造的 SkillDefinition，id 不为空且不为 blank，
 * name 不为空且不为 blank，instructions 不为空且不为 blank，
 * suggestedTools 不为 null，metadata 不为 null。</p>
 *
 * <p><b>Validates: Requirements P-4, AC-3.1, AC-3.2</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillDefinition属性测试 {

    // ── Property 5: 字段完整性不变量 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 5: SkillDefinition 字段完整性不变量")
    void 成功构造的SkillDefinition_必填字段非空非blank(
            @ForAll("validSkillDefinitions") SkillDefinition definition) {

        // **Validates: P-4, AC-3.1, AC-3.2**
        assertThat(definition.id())
                .as("id 不为 null 且不为 blank")
                .isNotNull()
                .isNotBlank();

        assertThat(definition.name())
                .as("name 不为 null 且不为 blank")
                .isNotNull()
                .isNotBlank();

        assertThat(definition.instructions())
                .as("instructions 不为 null 且不为 blank")
                .isNotNull()
                .isNotBlank();

        assertThat(definition.suggestedTools())
                .as("suggestedTools 不为 null")
                .isNotNull();

        assertThat(definition.metadata())
                .as("metadata 不为 null")
                .isNotNull();
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
        var instructionsArb = safeString(1, 100);

        var toolArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-')
                .ofMinLength(1).ofMaxLength(15)
                .filter(s -> !s.isBlank());
        var toolsArb = toolArb.list().ofMinSize(0).ofMaxSize(5);

        var metaKeyArb = Arbitraries.of("key1", "key2", "key3");
        var metaValueArb = safeString(1, 20);
        var metadataArb = Arbitraries.maps(metaKeyArb, metaValueArb)
                .ofMinSize(0).ofMaxSize(3);

        var sourceArb = Arbitraries.of(
                (SkillSource) new SkillSource.UserDefined("/tmp/test", null),
                new SkillSource.UserDefined("/tmp/test2", null)
        );

        return Combinators.combine(idArb, nameArb, descArb, instructionsArb, toolsArb, metadataArb, sourceArb)
                .as((id, name, desc, instructions, tools, metadata, source) ->
                        SkillDefinition.builder()
                                .id(id)
                                .name(name)
                                .description(desc)
                                .version("1.0.0")
                                .source(source)
                                .instructions(instructions)
                                .suggestedTools(tools)
                                .metadata(metadata)
                                .build()
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
