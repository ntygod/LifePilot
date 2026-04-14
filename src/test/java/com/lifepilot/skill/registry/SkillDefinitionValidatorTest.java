package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillDefinitionValidator.ValidationResult;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillDefinitionValidator} 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class SkillDefinitionValidatorTest {

    private DynamicToolRegistry toolRegistry;
    private SkillDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(DynamicToolRegistry.class);
        validator = new SkillDefinitionValidator(toolRegistry, new SkillConfigProperties());
        // 默认所有工具都存在，返回一个真实的 BuiltinTool 实例
        var dummyTool = BuiltinTool.builder()
                .id("dummy").name("dummy").description("dummy")
                .executionSemantics(com.lifepilot.tool.semantics.ToolExecutionSemantics.generic())
                .executor(input -> null)
                .build();
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.of(dummyTool));
    }

    /** 构建合法的 SkillDefinition。 */
    private SkillDefinition validDefinition() {
        return SkillDefinition.builder()
                .id("todo")
                .name("待办管理")
                .description("管理待办事项")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("你是待办管理助手，帮助用户管理日常待办事项。")
                .suggestedTools(List.of("todo.create"))
                .metadata(Map.of())
                .build();
    }

    // ─────────────────────────────────────────────
    //  合法定义校验
    // ─────────────────────────────────────────────

    @Test
    void 合法定义_校验通过() {
        ValidationResult result = validator.validate(validDefinition());
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void suggestedTools为空列表_校验通过() {
        var def = validDefinition().toBuilder().suggestedTools(List.of()).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  ID 格式校验
    // ─────────────────────────────────────────────

    @Test
    void ID含大写字母_校验失败() {
        var def = validDefinition().toBuilder().id("Todo").build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("ID 格式不合法"));
    }

    @Test
    void ID含下划线_校验失败() {
        var def = validDefinition().toBuilder().id("my_skill").build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("ID 格式不合法"));
    }

    @Test
    void ID超过64字符_校验失败() {
        var def = validDefinition().toBuilder().id("a".repeat(65)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("ID 格式不合法"));
    }

    @Test
    void ID含连字符和数字_校验通过() {
        var def = validDefinition().toBuilder().id("my-skill-123").build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  名称长度校验
    // ─────────────────────────────────────────────

    @Test
    void 名称超过128字符_校验失败() {
        var def = validDefinition().toBuilder().name("名".repeat(129)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("名称长度超过限制"));
    }

    @Test
    void 名称恰好128字符_校验通过() {
        var def = validDefinition().toBuilder().name("a".repeat(128)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  Instructions 长度校验
    // ─────────────────────────────────────────────

    @Test
    void Instructions超过10000字符_校验失败() {
        var def = validDefinition().toBuilder().instructions("x".repeat(10001)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Instructions 长度超过限制"));
    }

    @Test
    void Instructions恰好10000字符_校验通过() {
        var def = validDefinition().toBuilder().instructions("x".repeat(10000)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  suggestedTools 校验
    // ─────────────────────────────────────────────

    @Test
    void suggestedTools中工具未注册_仅警告不阻断() {
        when(toolRegistry.resolve("unknown-tool")).thenReturn(Optional.empty());
        var def = validDefinition().toBuilder().suggestedTools(List.of("unknown-tool")).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  多错误累积
    // ─────────────────────────────────────────────

    @Test
    void 多个校验错误_全部返回() {
        var def = validDefinition().toBuilder()
                .id("INVALID_ID!")
                .name("n".repeat(200))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(2);
    }

    // ─────────────────────────────────────────────
    //  ValidationResult 防御性拷贝
    // ─────────────────────────────────────────────

    @Test
    void ValidationResult_errors不可变() {
        var errors = new ArrayList<>(List.of("错误1"));
        var result = new ValidationResult(false, errors);
        errors.add("错误2");
        assertThat(result.errors()).hasSize(1);
    }
}
