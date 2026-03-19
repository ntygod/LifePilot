package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SkillDisclosureTool 单元测试 — 验证 load_skill 工具注册和执行逻辑。
 *
 * @author zsg
 * @since 2026-03-19
 */
class SkillDisclosureToolTest {

    private DynamicToolRegistry toolRegistry;
    private SkillActivator skillActivator;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(DynamicToolRegistry.class);
        skillActivator = mock(SkillActivator.class);
    }

    // ─────────────────────────────────────────────
    //  registerTools
    // ─────────────────────────────────────────────

    @Test
    void registerTools_注册load_skill到DynamicToolRegistry() {
        var tool = new SkillDisclosureTool(toolRegistry, skillActivator);

        tool.registerTools();

        var captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(toolRegistry).registerBuiltinTool(captor.capture());
        BuiltinTool registered = captor.getValue();
        assertThat(registered.id()).isEqualTo("load_skill");
        assertThat(registered.idempotent()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  load_skill 正常加载
    // ─────────────────────────────────────────────

    @Test
    void loadSkill_正常加载_返回指令和建议工具() {
        var activation = new SkillActivation("todo-manager", "请按以下步骤操作...",
                List.of("builtin.datastore.query"));
        when(skillActivator.activate("todo-manager")).thenReturn(activation);

        ToolResult result = invokeLoadSkill("todo-manager");

        assertThat(result.ok()).isTrue();
        assertThat((String) result.getData("skill_id")).isEqualTo("todo-manager");
        assertThat((String) result.getData("instructions")).isEqualTo("请按以下步骤操作...");
    }

    // ─────────────────────────────────────────────
    //  load_skill — Skill 不存在，返回引导提示
    // ─────────────────────────────────────────────

    @Test
    void loadSkill_不存在_返回引导调用generate_skill的提示() {
        when(skillActivator.activate("nonexistent"))
                .thenThrow(new SkillActivationException("Skill 不存在: nonexistent"));

        ToolResult result = invokeLoadSkill("nonexistent");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Skill 不存在");
        assertThat(result.error()).contains("generate_skill");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 创建工具并通过 executor 调用 load_skill。 */
    private ToolResult invokeLoadSkill(String skillId) {
        var tool = new SkillDisclosureTool(toolRegistry, skillActivator);
        tool.registerTools();

        var captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(toolRegistry, atLeastOnce()).registerBuiltinTool(captor.capture());
        BuiltinTool registered = captor.getValue();

        var input = new ToolInput("load_skill",
                Map.of("skill_id", skillId),
                JsonSchema.empty(), null, null);
        return registered.execute(input);
    }
}
