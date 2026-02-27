package com.lifepilot.skill.action;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * ChainActionExecutor 单元测试 — L1 回归后使用 TemplateAction 渲染。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class ChainActionExecutorTest {

    @Mock
    private SkillRegistry skillRegistry;

    private VariableResolver variableResolver;
    private SkillConfigProperties config;
    private ChainActionExecutor executor;

    @BeforeEach
    void setUp() {
        variableResolver = new VariableResolver();
        config = new SkillConfigProperties();
        executor = new ChainActionExecutor(skillRegistry, variableResolver, config);
    }

    @Test
    void 步骤数超过上限_返回错误() {
        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("skill-1", Map.of(), "out1"),
                new SkillAction.ChainStep("skill-2", Map.of(), "out2"),
                new SkillAction.ChainStep("skill-3", Map.of(), "out3"),
                new SkillAction.ChainStep("skill-4", Map.of(), "out4"),
                new SkillAction.ChainStep("skill-5", Map.of(), "out5"),
                new SkillAction.ChainStep("skill-6", Map.of(), "out6")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("步骤数超过上限");
    }

    @Test
    void 引用的SkillID不存在_返回错误() {
        when(skillRegistry.find("existing-skill")).thenReturn(Optional.of(createSkillDef("existing-skill")));
        when(skillRegistry.find("missing-skill")).thenReturn(Optional.empty());

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("existing-skill", Map.of(), "out1"),
                new SkillAction.ChainStep("missing-skill", Map.of(), "out2")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("missing-skill");
    }

    @Test
    void 空步骤列表_返回成功() {
        var action = new SkillAction.ChainAction(List.of());

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEmpty();
    }

    @Test
    void 单步骤执行成功_渲染systemPrompt() {
        SkillDefinition skillDef = createSkillDef("translate", "翻译助手: ${input}");
        when(skillRegistry.find("translate")).thenReturn(Optional.of(skillDef));

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("translate", Map.of("text", "hello"), "result1")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
        // TemplateAction 渲染 systemPrompt，input 变量被替换
        assertThat(result.output()).contains("翻译助手");
    }

    @Test
    void 多步骤串联_前一步输出注入后一步() {
        when(skillRegistry.find("step-a")).thenReturn(Optional.of(
                createSkillDef("step-a", "步骤A输出: 中间结果")));
        when(skillRegistry.find("step-b")).thenReturn(Optional.of(
                createSkillDef("step-b", "步骤B输入: ${input}")));

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("step-a", Map.of("input", "原始输入"), "outputA"),
                new SkillAction.ChainStep("step-b", Map.of("data", "${result.outputA}"), "outputB")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        assertThat(result.success()).isTrue();
    }

    @Test
    void 多步骤串联执行成功() {
        when(skillRegistry.find("ok-skill")).thenReturn(Optional.of(createSkillDef("ok-skill")));
        when(skillRegistry.find("next-skill")).thenReturn(Optional.of(createSkillDef("next-skill")));

        List<SkillAction.ChainStep> steps = List.of(
                new SkillAction.ChainStep("ok-skill", Map.of(), "out1"),
                new SkillAction.ChainStep("next-skill", Map.of(), "out2")
        );
        var action = new SkillAction.ChainAction(steps);

        ActionResult result = executor.execute(action, Map.of());

        // 两步都应成功（TemplateAction 渲染 systemPrompt）
        assertThat(result.success()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private SkillDefinition createSkillDef(String id) {
        return createSkillDef(id, "你是 " + id + " 助手");
    }

    private SkillDefinition createSkillDef(String id, String systemPrompt) {
        return SkillDefinition.builder()
                .id(id)
                .name(id + " Skill")
                .description("测试 Skill")
                .version("1.0")
                .source(new SkillSource.Builtin())
                .systemPrompt(systemPrompt)
                .allowedTools(List.of())
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }
}
