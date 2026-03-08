package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * IntrospectionSkillProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class IntrospectionSkillProviderTest {

    private CapabilityAggregator aggregator;
    private SkillRegistry skillRegistry;
    private AgentRegistry agentRegistry;
    private DynamicToolRegistry toolRegistry;
    private WorkflowRegistry workflowRegistry;
    private IntrospectionSkillProvider provider;

    @BeforeEach
    void setUp() {
        skillRegistry = mock(SkillRegistry.class);
        agentRegistry = mock(AgentRegistry.class);
        toolRegistry = mock(DynamicToolRegistry.class);
        workflowRegistry = mock(WorkflowRegistry.class);

        var properties = new MetaProperties();
        when(skillRegistry.listAll()).thenReturn(List.of());
        when(agentRegistry.listAll()).thenReturn(List.of());
        when(toolRegistry.getToolSnapshot()).thenReturn(List.of());
        when(workflowRegistry.listAll()).thenReturn(List.of());
        when(toolRegistry.getToolCountByLayer()).thenReturn(Map.of());

        aggregator = new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties);

        provider = new IntrospectionSkillProvider(
                aggregator, skillRegistry, agentRegistry, toolRegistry, workflowRegistry);
    }

    @Test
    void provide_返回正确的SkillDefinition() {
        var definition = provider.provide();

        assertThat(definition.id()).isEqualTo("builtin.introspection");
        assertThat(definition.name()).isEqualTo("系统自省");
        assertThat(definition.suggestedTools()).containsExactlyInAnyOrder(
                "system.list-capabilities",
                "system.explain",
                "system.status",
                "system.suggest"
        );
    }

    @Test
    void registerTools_注册4个工具() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, times(4)).registerBuiltinTool(captor.capture());

        var tools = captor.getAllValues();
        assertThat(tools).extracting(BuiltinTool::id)
                .containsExactlyInAnyOrder(
                        "system.list-capabilities",
                        "system.explain",
                        "system.status",
                        "system.suggest"
                );
    }

    @Test
    void registerTools_所有工具tags含infrastructure且风险等级LOW() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        for (BuiltinTool tool : captor.getAllValues()) {
            assertThat(tool.tags()).contains("infrastructure");
            assertThat(tool.riskLevel()).isEqualTo(RiskLevel.LOW);
        }
    }

    // ─────────────────────────────────────────────
    //  system.list-capabilities 测试
    // ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void listCapabilities_无过滤_返回全部分组() {
        var skill = createSkill("test.skill");
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.list-capabilities", Map.of());

        assertThat(result.ok()).isTrue();
        assertThat((int) result.getData("totalCount")).isEqualTo(1);
        assertThat((List<Map<String, Object>>) result.getData("skills")).hasSize(1);
    }

    @Test
    void listCapabilities_按类型过滤_只返回匹配类型() {
        var skill = createSkill("test.skill");
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.list-capabilities",
                Map.of("type", "skill"));

        assertThat(result.ok()).isTrue();
        assertThat((int) result.getData("count")).isEqualTo(1);

        // 过滤不存在的类型
        var emptyResult = executeToolByProvider("system.list-capabilities",
                Map.of("type", "workflow"));
        assertThat(emptyResult.ok()).isTrue();
        assertThat((int) emptyResult.getData("count")).isZero();
    }

    // ─────────────────────────────────────────────
    //  system.explain 测试
    // ─────────────────────────────────────────────

    @Test
    void explain_查找Tool_返回详细信息() {
        var tool = BuiltinTool.builder()
                .id("builtin.env.datetime")
                .name("获取日期时间")
                .description("获取当前日期时间")
                .riskLevel(RiskLevel.LOW)
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        when(toolRegistry.resolve("builtin.env.datetime")).thenReturn(Optional.of(tool));

        var result = executeToolByProvider("system.explain",
                Map.of("id", "builtin.env.datetime"));

        assertThat(result.ok()).isTrue();
        assertThat((String) result.getData("id")).isEqualTo("builtin.env.datetime");
        assertThat((String) result.getData("type")).isEqualTo("tool");
        assertThat((String) result.getData("riskLevel")).isEqualTo("LOW");
    }

    @Test
    void explain_查找Skill_返回详细信息() {
        var skill = createSkill("test.skill");
        when(skillRegistry.find("test.skill")).thenReturn(Optional.of(skill));
        when(toolRegistry.resolve("test.skill")).thenReturn(Optional.empty());

        var result = executeToolByProvider("system.explain",
                Map.of("id", "test.skill"));

        assertThat(result.ok()).isTrue();
        assertThat((String) result.getData("id")).isEqualTo("test.skill");
        assertThat((String) result.getData("type")).isEqualTo("skill");
    }

    @Test
    void explain_指定类型_直接路由() {
        var agent = AgentDefinition.builder()
                .id("test.agent")
                .name("测试 Agent")
                .description("测试描述")
                .systemPrompt("测试")
                .allowedTools(List.of())
                .canDelegate(false)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();
        when(agentRegistry.find("test.agent")).thenReturn(Optional.of(agent));

        var result = executeToolByProvider("system.explain",
                Map.of("id", "test.agent", "type", "agent"));

        assertThat(result.ok()).isTrue();
        assertThat((String) result.getData("type")).isEqualTo("agent");
    }

    @Test
    void explain_未找到_返回错误() {
        when(toolRegistry.resolve("nonexistent")).thenReturn(Optional.empty());
        when(skillRegistry.find("nonexistent")).thenReturn(Optional.empty());
        when(agentRegistry.find("nonexistent")).thenReturn(Optional.empty());
        when(workflowRegistry.find("nonexistent")).thenReturn(Optional.empty());

        var result = executeToolByProvider("system.explain",
                Map.of("id", "nonexistent"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("nonexistent");
    }

    // ─────────────────────────────────────────────
    //  system.status 测试
    // ─────────────────────────────────────────────

    @Test
    void status_返回各注册中心计数() {
        var skill = createSkill("test.skill");
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.status", Map.of());

        assertThat(result.ok()).isTrue();
        assertThat((int) result.getData("skillCount")).isEqualTo(1);
        assertThat((int) result.getData("agentCount")).isZero();
        assertThat((int) result.getData("toolCount")).isZero();
        assertThat((Object) result.getData("totalCapabilities")).isNotNull();
        assertThat((Object) result.getData("jvmMemoryUsedMB")).isNotNull();
    }

    // ─────────────────────────────────────────────
    //  system.suggest 测试
    // ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void suggest_关键词匹配_返回匹配结果() {
        var skill = createSkill("todo.manager");
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        when(skillRegistry.search("todo")).thenReturn(List.of());
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.suggest",
                Map.of("query", "todo"));

        assertThat(result.ok()).isTrue();
        assertThat((int) result.getData("matchCount")).isGreaterThan(0);
        var suggestions = (List<Map<String, Object>>) result.getData("suggestions");
        assertThat(suggestions).isNotEmpty();
    }

    @Test
    void suggest_无匹配_返回提示信息() {
        when(skillRegistry.search("不存在的功能")).thenReturn(List.of());
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.suggest",
                Map.of("query", "不存在的功能"));

        assertThat(result.ok()).isTrue();
        assertThat((int) result.getData("matchCount")).isZero();
        assertThat((String) result.getData("hint")).isNotBlank();
    }

    @Test
    void suggest_语义搜索异常_降级为仅关键词匹配() {
        when(skillRegistry.search(anyString())).thenThrow(new RuntimeException("搜索异常"));
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.suggest",
                Map.of("query", "test"));

        // 不应抛异常，应正常返回
        assertThat(result.ok()).isTrue();
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 通过 provider 注册工具后，按 ID 查找并执行。 */
    private ToolResult executeToolByProvider(String toolId, Map<String, Object> params) {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);
        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);

        provider.registerTools(registry);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        var tool = captor.getAllValues().stream()
                .filter(t -> t.id().equals(toolId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到工具: " + toolId));

        var input = new ToolInput(toolId, params, tool.inputSchema(), null);
        return tool.execute(input);
    }

    /** 创建测试用 SkillDefinition。 */
    private SkillDefinition createSkill(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("Skill " + id)
                .description("描述 " + id)
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("测试")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();
    }
}
