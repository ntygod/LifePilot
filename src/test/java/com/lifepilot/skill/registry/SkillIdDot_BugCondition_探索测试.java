package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillDefinitionValidator.ValidationResult;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import net.jqwik.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bug Condition 探索测试 — Skill ID 点号校验失败。
 *
 * <p>Property 1: 对任意 ID 匹配 {@code ^[a-z0-9.-]{1,64}$} 且包含点号的 SkillDefinition，
 * 修复后的 {@link SkillDefinitionValidator#validate(SkillDefinition)} 应返回
 * {@code ValidationResult(valid=true, errors=[])}。</p>
 *
 * <p>当前代码的正则为 {@code ^[a-z0-9-]{1,64}$}（缺少点号），
 * 因此本测试在未修复代码上预期失败，以确认 bug 存在。</p>
 *
 * <p><b>Validates: Requirements 1.1, 2.1</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillIdDot_BugCondition_探索测试 {

    private final DynamicToolRegistry toolRegistry = mock(DynamicToolRegistry.class);
    private final SkillDefinitionValidator validator =
            new SkillDefinitionValidator(toolRegistry, new SkillConfigProperties());

    {
        // 默认所有工具都存在
        var dummyTool = BuiltinTool.builder()
                .id("dummy").name("dummy").description("dummy")
                .executor(input -> null)
                .build();
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.of(dummyTool));
    }

    // ── Property 1: Bug Condition — 包含点号的合法 ID 应通过校验 ──

    @Property(tries = 100)
    @Label("Property 1: 包含点号的合法 Skill ID 应通过校验")
    void 包含点号的合法ID_应通过校验(
            @ForAll("validIdsWithDots") String id) {

        // **Validates: Requirements 1.1, 2.1**
        var definition = SkillDefinition.builder()
                .id(id)
                .name("测试 Skill")
                .description("测试描述")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("测试指令内容")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();

        ValidationResult result = validator.validate(definition);

        assertThat(result.valid())
                .as("ID '%s' 匹配 ^[a-z0-9.-]{1,64}$ 且包含点号，应通过校验", id)
                .isTrue();
        assertThat(result.errors())
                .as("校验通过时错误列表应为空")
                .isEmpty();
    }

    // ── 生成器：生成包含至少一个点号的合法 Skill ID ──

    @Provide
    Arbitrary<String> validIdsWithDots() {
        // 生成由 [a-z0-9-] 组成的片段，用点号连接，确保至少包含一个点号
        var segmentArb = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('-')
                .ofMinLength(1).ofMaxLength(10)
                .filter(s -> s.matches("^[a-z0-9-]+$"));

        // 2-4 个片段用点号连接，确保总长度 ≤ 64
        return segmentArb.list().ofMinSize(2).ofMaxSize(4)
                .map(segments -> String.join(".", segments))
                .filter(id -> id.length() >= 1 && id.length() <= 64)
                .filter(id -> id.matches("^[a-z0-9.-]{1,64}$"));
    }
}
