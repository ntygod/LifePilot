package com.lifepilot.skill.registry;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillDefinitionValidator.ValidationResult;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
                .source(new SkillSource.Builtin())
                .systemPrompt("你是待办管理助手")
                .allowedTools(List.of("builtin.todo.create"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
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
    //  System Prompt 长度校验
    // ─────────────────────────────────────────────

    @Test
    void SystemPrompt超过10000字符_校验失败() {
        var def = validDefinition().toBuilder().systemPrompt("x".repeat(10001)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("System Prompt 长度超过限制"));
    }

    @Test
    void SystemPrompt恰好10000字符_校验通过() {
        var def = validDefinition().toBuilder().systemPrompt("x".repeat(10000)).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  工具列表校验
    // ─────────────────────────────────────────────

    @Test
    void 工具列表为空_校验失败() {
        var def = validDefinition().toBuilder().allowedTools(List.of()).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("工具列表不能为空"));
    }

    @Test
    void 工具未注册_校验失败() {
        when(toolRegistry.resolve("unknown-tool")).thenReturn(Optional.empty());
        var def = validDefinition().toBuilder().allowedTools(List.of("unknown-tool")).build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("工具未注册: unknown-tool"));
    }

    // ─────────────────────────────────────────────
    //  AUTO_GENERATED 额外限制
    // ─────────────────────────────────────────────

    @Test
    void AutoGenerated_maxTokens超限_校验失败() {
        var def = validDefinition().toBuilder()
                .source(new SkillSource.AutoGenerated("trace-1"))
                .budget(new SkillBudget(10001, 10, 120, 50))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("maxTokens 超过限制"));
    }

    @Test
    void AutoGenerated_maxSteps超限_校验失败() {
        var def = validDefinition().toBuilder()
                .source(new SkillSource.AutoGenerated("trace-1"))
                .budget(new SkillBudget(8000, 16, 120, 50))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("maxSteps 超过限制"));
    }

    @Test
    void AutoGenerated_timeoutSeconds超限_校验失败() {
        var def = validDefinition().toBuilder()
                .source(new SkillSource.AutoGenerated("trace-1"))
                .budget(new SkillBudget(8000, 10, 181, 50))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("timeoutSeconds 超过限制"));
    }

    @Test
    void AutoGenerated_预算在限制内_校验通过() {
        var def = validDefinition().toBuilder()
                .source(new SkillSource.AutoGenerated("trace-1"))
                .budget(new SkillBudget(10000, 15, 180, 50))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void Builtin来源_不受AutoGenerated额外限制() {
        var def = validDefinition().toBuilder()
                .source(new SkillSource.Builtin())
                .budget(new SkillBudget(20000, 20, 300, 200))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  多错误累积
    // ─────────────────────────────────────────────

    @Test
    void 多个校验错误_全部返回() {
        when(toolRegistry.resolve("unknown")).thenReturn(Optional.empty());
        var def = validDefinition().toBuilder()
                .id("INVALID_ID!")
                .name("n".repeat(200))
                .allowedTools(List.of("unknown"))
                .build();
        ValidationResult result = validator.validate(def);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(3);
    }

    // ─────────────────────────────────────────────
    //  ValidationResult 防御性拷贝
    // ─────────────────────────────────────────────

    @Test
    void ValidationResult_errors不可变() {
        var errors = new java.util.ArrayList<>(List.of("错误1"));
        var result = new ValidationResult(false, errors);
        errors.add("错误2");
        assertThat(result.errors()).hasSize(1);
    }
}
