package com.lifepilot.skill.disclosure;

import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
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
    private SkillRegistry skillRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(DynamicToolRegistry.class);
        skillActivator = mock(SkillActivator.class);
        skillRegistry = mock(SkillRegistry.class);
    }

    // ─────────────────────────────────────────────
    //  registerTools
    // ─────────────────────────────────────────────

    @Test
    void registerTools_注册load_skill到DynamicToolRegistry() {
        var tool = new SkillDisclosureTool(toolRegistry, skillActivator, skillRegistry);

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
                List.of("datastore.query"));
        when(skillActivator.activate("todo-manager")).thenReturn(activation);

        ToolResult result = invokeLoadSkill("todo-manager");
        Integer loadedCount = result.getData("loaded_count");
        List<String> missingSkills = result.getData("missing_skills");
        List<String> suggestedTools = result.getData("all_suggested_tools");
        List<Map<String, Object>> skills = result.getData("skills");

        assertThat(result.ok()).isTrue();
        assertThat(loadedCount).isEqualTo(1);
        assertThat(missingSkills).isEqualTo(List.of());
        assertThat(suggestedTools).isEqualTo(List.of("datastore.query"));
        assertThat(skills)
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.get("skill_id")).isEqualTo("todo-manager");
                    assertThat(skill.get("instructions")).isEqualTo("请按以下步骤操作...");
                    assertThat(skill.get("suggested_tools")).isEqualTo(List.of("datastore.query"));
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadSkill_部分不存在_返回部分成功和缺失列表() {
        var activation = new SkillActivation("todo-manager", "请按以下步骤操作...",
                List.of("datastore.query"));
        when(skillActivator.activate("todo-manager")).thenReturn(activation);
        when(skillActivator.activate("missing-skill"))
                .thenThrow(new SkillActivationException("Skill 不存在: missing-skill"));

        ToolResult result = invokeLoadSkill("todo-manager", "missing-skill");
        Integer loadedCount = result.getData("loaded_count");
        List<String> missingSkills = result.getData("missing_skills");
        List<Map<String, Object>> skills = result.getData("skills");

        assertThat(result.ok()).isFalse();
        assertThat(result.status().name()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.error()).contains("missing-skill");
        assertThat(loadedCount).isEqualTo(1);
        assertThat(missingSkills).isEqualTo(List.of("missing-skill"));
        assertThat(skills)
                .extracting(skill -> skill.get("skill_id"))
                .containsExactly("todo-manager");
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
    //  L1 搜索测试
    // ─────────────────────────────────────────────

    @Test
    void searchSkills_正常搜索_返回匹配的技能元数据() {
        var skill = SkillDefinition.builder()
                .id("todo-manager")
                .name("任务管理")
                .description("管理待办任务和项目跟踪")
                .version("1.0")
                .instructions("instructions")
                .suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null))
                .metadata(Map.of())
                .build();
        when(skillRegistry.search("任务管理", 5)).thenReturn(List.of(skill));

        ToolResult result = invokeSearch("任务管理");

        assertThat(result.ok()).isTrue();
        Integer found = result.getData("found");
        assertThat(found).isEqualTo(1);
        List<Map<String, Object>> skills = result.getData("skills");
        assertThat(skills).singleElement().satisfies(s -> {
            assertThat(s.get("skill_id")).isEqualTo("todo-manager");
            assertThat(s.get("name")).isEqualTo("任务管理");
            assertThat(s.get("description")).isEqualTo("管理待办任务和项目跟踪");
        });
    }

    @Test
    void searchSkills_无匹配_返回空结果和提示() {
        when(skillRegistry.search("不存在的能力", 5)).thenReturn(List.of());

        ToolResult result = invokeSearch("不存在的能力");

        assertThat(result.ok()).isTrue();
        Integer found = result.getData("found");
        assertThat(found).isEqualTo(0);
        String hint = result.getData("hint");
        assertThat(hint).contains("未找到匹配的技能");
    }

    @Test
    void searchSkills_缺少query参数_返回错误() {
        ToolResult result = invokeWithParams(Map.of("action", "search"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("query");
    }

    @Test
    void loadSkill_未知action_返回错误() {
        ToolResult result = invokeWithParams(Map.of("action", "unknown"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("未知 action");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 创建工具并通过 executor 调用 load_skill（action=load）。 */
    private ToolResult invokeLoadSkill(String... skillIds) {
        return invokeWithParams(Map.of("skill_ids", List.of(skillIds)));
    }

    /** 创建工具并通过 executor 调用 load_skill（action=search）。 */
    private ToolResult invokeSearch(String query) {
        return invokeWithParams(Map.of("action", "search", "query", query));
    }

    /** 创建工具并通过 executor 以指定参数调用 load_skill。 */
    private ToolResult invokeWithParams(Map<String, Object> params) {
        var tool = new SkillDisclosureTool(toolRegistry, skillActivator, skillRegistry);
        tool.registerTools();

        var captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(toolRegistry, atLeastOnce()).registerBuiltinTool(captor.capture());
        BuiltinTool registered = captor.getValue();

        var input = new ToolInput("load_skill", params, JsonSchema.empty(), null, null);
        return registered.execute(input);
    }
}
