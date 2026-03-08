package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentRegistryEvent;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.event.ToolRegistryEvent;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CapabilityAggregator 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class CapabilityAggregatorTest {

    private SkillRegistry skillRegistry;
    private AgentRegistry agentRegistry;
    private DynamicToolRegistry toolRegistry;
    private WorkflowRegistry workflowRegistry;
    private CapabilityAggregator aggregator;

    @BeforeEach
    void setUp() {
        skillRegistry = mock(SkillRegistry.class);
        agentRegistry = mock(AgentRegistry.class);
        toolRegistry = mock(DynamicToolRegistry.class);
        workflowRegistry = mock(WorkflowRegistry.class);

        var properties = new MetaProperties();
        // 设置较短的 TTL 便于测试过期
        properties.getIntrospection().setCacheTtlSeconds(1);

        aggregator = new CapabilityAggregator(
                skillRegistry, agentRegistry, toolRegistry, workflowRegistry, properties);

        // 默认返回空列表
        when(skillRegistry.listAll()).thenReturn(List.of());
        when(agentRegistry.listAll()).thenReturn(List.of());
        when(toolRegistry.getToolSnapshot()).thenReturn(List.of());
        when(workflowRegistry.listAll()).thenReturn(List.of());
    }

    @Test
    void aggregate_空注册中心_返回空摘要() {
        var summary = aggregator.aggregate();

        assertThat(summary.skills()).isEmpty();
        assertThat(summary.agents()).isEmpty();
        assertThat(summary.tools()).isEmpty();
        assertThat(summary.workflows()).isEmpty();
        assertThat(summary.mcpServers()).isEmpty();
        assertThat(summary.totalCount()).isZero();
    }

    @Test
    void aggregate_聚合各注册中心数据() {
        // 准备 Skill
        var skill = SkillDefinition.builder()
                .id("test.skill")
                .name("测试 Skill")
                .description("测试描述")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("测试")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));

        // 准备 Agent
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
        when(agentRegistry.listAll()).thenReturn(List.of(agent));

        // 准备 Tool
        var tool = BuiltinTool.builder()
                .id("test.tool")
                .name("测试工具")
                .description("测试描述")
                .riskLevel(RiskLevel.LOW)
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        when(toolRegistry.getToolSnapshot()).thenReturn(List.of(tool));

        // 准备 Workflow
        var workflow = WorkflowDefinition.builder()
                .id("test.workflow")
                .name("测试工作流")
                .description("测试描述")
                .version("1.0.0")
                .enabled(true)
                .steps(List.of())
                .build();
        when(workflowRegistry.listAll()).thenReturn(List.of(workflow));

        var summary = aggregator.aggregate();

        assertThat(summary.skills()).hasSize(1);
        assertThat(summary.skills().getFirst().id()).isEqualTo("test.skill");
        assertThat(summary.skills().getFirst().source()).isEqualTo("builtin");
        assertThat(summary.skills().getFirst().type()).isEqualTo("skill");

        assertThat(summary.agents()).hasSize(1);
        assertThat(summary.agents().getFirst().id()).isEqualTo("test.agent");

        assertThat(summary.tools()).hasSize(1);
        assertThat(summary.tools().getFirst().id()).isEqualTo("test.tool");

        assertThat(summary.workflows()).hasSize(1);
        assertThat(summary.workflows().getFirst().id()).isEqualTo("test.workflow");

        assertThat(summary.totalCount()).isEqualTo(4);
    }

    @Test
    void aggregate_缓存命中_不重复调用注册中心() {
        aggregator.aggregate();
        aggregator.aggregate();

        // 只调用一次（第二次走缓存）
        verify(skillRegistry, times(1)).listAll();
        verify(agentRegistry, times(1)).listAll();
    }

    @Test
    void aggregate_缓存TTL过期_重新聚合() throws InterruptedException {
        aggregator.aggregate();

        // 等待缓存过期（TTL = 1s）
        Thread.sleep(1100);

        aggregator.aggregate();

        // 过期后重新调用
        verify(skillRegistry, times(2)).listAll();
    }

    @Test
    void onSkillRegistryEvent_触发缓存失效() {
        // 先聚合一次填充缓存
        aggregator.aggregate();
        verify(skillRegistry, times(1)).listAll();

        // 触发事件
        aggregator.onSkillRegistryEvent(new SkillRegistryEvent.SkillUnregistered("some.skill"));

        // 再次聚合应重新调用
        aggregator.aggregate();
        verify(skillRegistry, times(2)).listAll();
    }

    @Test
    void onToolRegistryEvent_触发缓存失效() {
        aggregator.aggregate();
        // doAggregate 调用 getToolSnapshot() 两次（工具列表 + MCP Server 推断）
        verify(toolRegistry, times(2)).getToolSnapshot();

        aggregator.onToolRegistryEvent(new ToolRegistryEvent.ToolsRegistered(
                List.of("new.tool"), ToolLayer.JAVA_NATIVE, "test"));

        aggregator.aggregate();
        verify(toolRegistry, times(4)).getToolSnapshot();
    }

    @Test
    void onAgentRegistryEvent_触发缓存失效() {
        aggregator.aggregate();
        verify(agentRegistry, times(1)).listAll();

        aggregator.onAgentRegistryEvent(new AgentRegistryEvent.AgentUnregistered("some.agent"));

        aggregator.aggregate();
        verify(agentRegistry, times(2)).listAll();
    }

    @Test
    void filterByType_按类型过滤() {
        var skill = SkillDefinition.builder()
                .id("test.skill")
                .name("测试")
                .description("测试")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("测试")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));

        var result = aggregator.filterByType("skill");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo("skill");

        var empty = aggregator.filterByType("unknown");
        assertThat(empty).isEmpty();
    }

    @Test
    void aggregate_SkillSource映射正确() {
        var builtinSkill = createSkill("s1", new SkillSource.Builtin());
        var userSkill = createSkill("s2", new SkillSource.UserDefined("/path"));
        var autoSkill = createSkill("s3", new SkillSource.AutoGenerated("trace-1"));
        var marketSkill = createSkill("s4", new SkillSource.Marketplace("pkg-1", "https://example.com", java.time.Instant.now()));

        when(skillRegistry.listAll()).thenReturn(List.of(builtinSkill, userSkill, autoSkill, marketSkill));

        var summary = aggregator.aggregate();
        var sources = summary.skills().stream()
                .collect(java.util.stream.Collectors.toMap(CapabilityInfo::id, CapabilityInfo::source));

        assertThat(sources.get("s1")).isEqualTo("builtin");
        assertThat(sources.get("s2")).isEqualTo("yaml");
        assertThat(sources.get("s3")).isEqualTo("auto-generated");
        assertThat(sources.get("s4")).isEqualTo("marketplace");
    }

    @Test
    void aggregate_WorkflowEnabled映射为active_Disabled映射为inactive() {
        var enabled = WorkflowDefinition.builder()
                .id("w1").name("启用").description("").version("1.0.0")
                .enabled(true).steps(List.of()).build();
        var disabled = WorkflowDefinition.builder()
                .id("w2").name("禁用").description("").version("1.0.0")
                .enabled(false).steps(List.of()).build();
        when(workflowRegistry.listAll()).thenReturn(List.of(enabled, disabled));

        var summary = aggregator.aggregate();
        var statuses = summary.workflows().stream()
                .collect(java.util.stream.Collectors.toMap(CapabilityInfo::id, CapabilityInfo::status));

        assertThat(statuses.get("w1")).isEqualTo("active");
        assertThat(statuses.get("w2")).isEqualTo("inactive");
    }

    /** 辅助方法：创建测试用 SkillDefinition。 */
    private SkillDefinition createSkill(String id, SkillSource source) {
        return SkillDefinition.builder()
                .id(id)
                .name("Skill " + id)
                .description("描述 " + id)
                .version("1.0.0")
                .source(source)
                .instructions("测试")
                .suggestedTools(List.of())
                .metadata(Map.of())
                .build();
    }
}
