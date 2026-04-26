package com.lifepilot.meta.convenience;

import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * IntrospectionToolProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class IntrospectionToolProviderTest {

    private CapabilityAggregator aggregator;
    private SkillRegistry skillRegistry;
    private AgentRegistry agentRegistry;
    private DynamicToolRegistry toolRegistry;
    private WorkflowRegistry workflowRegistry;
    private IntrospectionToolProvider provider;
    private WorkflowRepository workflowRepository;
    private McpServerRegistry mcpServerRegistry;

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

        var sharedScheduler = mock(SharedScheduler.class);
        when(sharedScheduler.debounce()).thenReturn(
                java.util.concurrent.Executors.newScheduledThreadPool(1));

        aggregator = new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties, sharedScheduler);

        provider = new IntrospectionToolProvider(
                aggregator, toolRegistry, workflowRegistry, workflowRepository, mcpServerRegistry);
    }

    @Test
    void registerTools_注册全部自省工具() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        var expectedToolIds = List.of(
                "system.status"
        );
        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, times(expectedToolIds.size())).registerBuiltinTool(captor.capture());

        var tools = captor.getAllValues();
        assertThat(tools).extracting(BuiltinTool::id)
                .containsExactlyInAnyOrderElementsOf(expectedToolIds);
    }

    @Test
    void registerTools_所有工具均带非空tags且风险等级LOW() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        for (BuiltinTool tool : captor.getAllValues()) {
            assertThat(tool.tags()).as("tool %s", tool.id()).isNotEmpty();
            assertThat(tool.riskLevel()).isEqualTo(RiskLevel.LOW);
        }
    }

    // ─────────────────────────────────────────────
    //  system.status 测试
    // ─────────────────────────────────────────────

    @Test
    void status_返回各注册中心计数() {
        var skill = createSkill("test.skill");
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        when(toolRegistry.getToolCountByLayer()).thenReturn(Map.of(
                com.lifepilot.tool.model.ToolLayer.JAVA_NATIVE, 5,
                com.lifepilot.tool.model.ToolLayer.MCP_EXTERNAL, 1
        ));
        aggregator.invalidateCache();

        var result = executeToolByProvider("system.status", Map.of());

        assertThat(result.ok()).isTrue();
        assertThat((int) result.getData("skillCount")).isEqualTo(1);
        assertThat((int) result.getData("agentCount")).isZero();
        assertThat((int) result.getData("toolCount")).isZero();
        assertThat((Object) result.getData("totalCapabilities")).isNotNull();
        assertThat((Object) result.getData("jvmMemoryUsedMB")).isNotNull();
        @SuppressWarnings("unchecked")
        var toolLayerDistribution = (Map<String, Object>) result.getData("toolLayerDistribution");
        assertThat(toolLayerDistribution)
                .isEqualTo(Map.of("JAVA_NATIVE", 5, "MCP_EXTERNAL", 1));
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
                .orElseThrow(() -> new AssertionError("未找到工具 " + toolId));

        var input = new ToolInput(toolId, params, tool.inputSchema(), null, null);
        return tool.execute(input);
    }

    /** 创建测试用 SkillDefinition。 */
    private SkillDefinition createSkill(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("Skill " + id)
                .description("描述 " + id)
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("测试")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();
    }
}
