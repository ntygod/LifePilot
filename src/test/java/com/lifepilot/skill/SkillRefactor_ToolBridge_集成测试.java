package com.lifepilot.skill;

import com.lifepilot.meta.convenience.CapabilityAggregator;
import com.lifepilot.meta.convenience.CapabilityInfo;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.bridge.SkillToToolBridge;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Skill 系统重构 �?SkillToToolBridge 跨模块集成测试�? *
 * <p>验证 skills 工具注册�?DynamicToolRegistry、list_skills �?activate_skill 端到端流程�? * 使用真实 SkillRegistry + SkillActivator + SkillToToolBridge + DynamicToolRegistry�? * Mock 外部依赖（LlmRouter、GuardrailEngine）�?/p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillRefactor_ToolBridge_集成测试 {

    private SkillRegistry skillRegistry;
    private DynamicToolRegistry toolRegistry;
    private SkillToToolBridge skillToToolBridge;
    private SkillMetricsTracker metricsTracker;
    private CapabilityAggregator mockCapabilityAggregator;

    @BeforeEach
    void setUp() {
        // 1. 构建真实 SkillRegistry
        var toolRegistryForValidator = mock(DynamicToolRegistry.class);
        when(toolRegistryForValidator.resolve(anyString())).thenReturn(
                Optional.of(com.lifepilot.tool.BuiltinTool.builder()
                        .id("dummy").name("dummy").description("测试工具")
                        .executor(input -> null).build()));

        var skillConfig = new SkillConfigProperties();
        var validator = new SkillDefinitionValidator(toolRegistryForValidator, skillConfig);
        var searchIndex = mock(SkillSearchIndex.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        skillRegistry = new SkillRegistry(validator, searchIndex, eventPublisher, skillConfig);

        // 2. 构建真实 DynamicToolRegistry
        var guardrailEngine = mock(GuardrailEngine.class);
        toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);

        // 3. 构建真实 SkillActivator
        metricsTracker = new SkillMetricsTracker();
        var skillActivator = new SkillActivator(skillRegistry, metricsTracker, eventPublisher);

        // 4. Mock CapabilityAggregator
        mockCapabilityAggregator = mock(CapabilityAggregator.class);
        when(mockCapabilityAggregator.filterByType("skill")).thenReturn(List.of());

        // 5. 构建真实 SkillToToolBridge
        skillToToolBridge = new SkillToToolBridge(toolRegistry, skillActivator, mockCapabilityAggregator);
    }

    // ─────────────────────────────────────────────
    //  skills 工具注册
    // ─────────────────────────────────────────────

    @Test
    void registerSkillsTool_注册后_DynamicToolRegistry可解析skills工具() {
        skillToToolBridge.registerSkillsTool();

        Optional<ToolContract> resolved = toolRegistry.resolve("skills");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo("skills");
        assertThat(resolved.get().name()).isEqualTo("Skill 管理");
    }

    // ─────────────────────────────────────────────
    //  list_skills 端到�?    // ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void listSkills_返回已注册Skill的摘�?) {
        // 注册测试 Skill
        registerTestSkill("todo", "待办管理", "管理待办事项");
        registerTestSkill("schedule", "日程管理", "管理日程安排");

        // 配置 CapabilityAggregator 返回对应�?CapabilityInfo
        when(mockCapabilityAggregator.filterByType("skill")).thenReturn(List.of(
                new CapabilityInfo("todo", "待办管理", "管理待办事项", "builtin", "active", "skill"),
                new CapabilityInfo("schedule", "日程管理", "管理日程安排", "builtin", "active", "skill")
        ));

        // 注册 skills 工具
        skillToToolBridge.registerSkillsTool();

        // 调用 list_skills
        ToolInput input = new ToolInput("skills",
                Map.of("action", "list_skills"),
                JsonSchema.empty(), null, null);
        ToolResult result = toolRegistry.resolve("skills").get().execute(input);

        assertThat(result.ok()).isTrue();
        List<String> skills = (List<String>) result.data().get("skills");
        assertThat(skills).hasSize(2);
        assertThat(skills).anyMatch(s -> s.contains("todo"));
        assertThat(skills).anyMatch(s -> s.contains("schedule"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSkills_无Skill时_返回空列�?) {
        when(mockCapabilityAggregator.filterByType("skill")).thenReturn(List.of());

        skillToToolBridge.registerSkillsTool();

        ToolInput input = new ToolInput("skills",
                Map.of("action", "list_skills"),
                JsonSchema.empty(), null, null);
        ToolResult result = toolRegistry.resolve("skills").get().execute(input);

        assertThat(result.ok()).isTrue();
        List<String> skills = (List<String>) result.data().get("skills");
        assertThat(skills).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  activate_skill 端到�?    // ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void activateSkill_激活成功_返回instructions和suggestedTools() {
        registerTestSkill("todo", "待办管理", "管理待办事项",
                "你是待办管理助手，帮助用户管理待办事项�?,
                List.of("builtin.todo.create", "builtin.todo.list"));

        skillToToolBridge.registerSkillsTool();

        ToolInput input = new ToolInput("skills",
                Map.of("action", "activate_skill", "skill_id", "todo"),
                JsonSchema.empty(), null, null);
        ToolResult result = toolRegistry.resolve("skills").get().execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("skill_id")).isEqualTo("todo");
        assertThat(result.data().get("instructions")).isEqualTo("你是待办管理助手，帮助用户管理待办事项�?);
        List<String> suggestedTools = (List<String>) result.data().get("suggested_tools");
        assertThat(suggestedTools).containsExactly("builtin.todo.create", "builtin.todo.list");
    }

    @Test
    void activateSkill_Skill不存在_返回错误() {
        skillToToolBridge.registerSkillsTool();

        ToolInput input = new ToolInput("skills",
                Map.of("action", "activate_skill", "skill_id", "nonexistent"),
                JsonSchema.empty(), null, null);
        ToolResult result = toolRegistry.resolve("skills").get().execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("nonexistent");
    }

    @Test
    void activateSkill_激活后_MetricsTracker记录激活次�?) {
        registerTestSkill("todo", "待办管理", "管理待办事项");

        skillToToolBridge.registerSkillsTool();

        ToolInput input = new ToolInput("skills",
                Map.of("action", "activate_skill", "skill_id", "todo"),
                JsonSchema.empty(), null, null);
        toolRegistry.resolve("skills").get().execute(input);
        toolRegistry.resolve("skills").get().execute(input);

        assertThat(metricsTracker.getActivationCount("todo")).isEqualTo(2);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private void registerTestSkill(String id, String name, String description) {
        registerTestSkill(id, name, description, "默认指令内容", List.of());
    }

    private void registerTestSkill(String id, String name, String description,
                                   String instructions, List<String> suggestedTools) {
        SkillDefinition definition = SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions(instructions)
                .suggestedTools(suggestedTools)
                .metadata(Map.of())
                .build();
        skillRegistry.register(definition);
    }
}
