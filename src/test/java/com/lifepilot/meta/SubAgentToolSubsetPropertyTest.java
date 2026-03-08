package com.lifepilot.meta;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import net.jqwik.api.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SubAgent 工具集子集属性测试。
 *
 * <p>使用 jqwik 生成随机 parentTools 和 childTools 列表，通过反射调用
 * {@code AgentExecutor.buildAllowedToolIds(AgentDefinition, AgentState)}，
 * 验证：结果（排除 infrastructure 工具）⊆ parentTools。</p>
 *
 * <p><b>Validates: Requirements 1.2, Property 2: SubAgent 工具集 ⊆ 父 Agent 工具集 ∪ Infrastructure</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SubAgentToolSubsetPropertyTest {

    /** Infrastructure 工具 ID 列表。 */
    private static final List<String> INFRA_TOOL_IDS = List.of(
            "builtin.env.datetime", "builtin.env.user-profile", "builtin.env.system-info",
            "builtin.web.search", "builtin.web.fetch",
            "builtin.reason.think", "builtin.reason.calculate",
            "builtin.shell.exec",
            "builtin.browser.navigate", "builtin.browser.click",
            "builtin.browser.input", "builtin.browser.screenshot",
            "builtin.code.execute",
            "builtin.file.read", "builtin.file.write",
            "builtin.file.list", "builtin.file.search",
            "builtin.interact.confirm", "builtin.interact.choose",
            "builtin.interact.input", "builtin.interact.notify"
    );

    /** 非 infrastructure 业务工具 ID 候选池。 */
    private static final List<String> BUSINESS_TOOL_IDS = List.of(
            "todo.create", "todo.list", "todo.delete",
            "schedule.create", "schedule.list",
            "habit.create", "habit.track",
            "memory.search", "memory.save",
            "handoff_to_writer", "handoff_to_analyst",
            "mcp.weather.query", "yaml.custom.tool"
    );

    /** Infrastructure 工具 ID 集合（用于快速查找）。 */
    private static final Set<String> INFRA_TOOL_ID_SET = Set.copyOf(INFRA_TOOL_IDS);

    /** 所有已注册工具（infrastructure + 业务工具）。 */
    private static final List<ToolContract> ALL_TOOLS;

    static {
        var tools = new ArrayList<ToolContract>();
        for (String id : INFRA_TOOL_IDS) {
            tools.add(buildTool(id, List.of("infrastructure"), riskLevelFor(id)));
        }
        for (String id : BUSINESS_TOOL_IDS) {
            tools.add(buildTool(id, List.of("skill"), RiskLevel.LOW));
        }
        ALL_TOOLS = List.copyOf(tools);
    }

    // ─────────────────────────────────────────────
    //  属性测试 — SubAgent 工具集 ⊆ 父 Agent 工具集 ∪ Infrastructure
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 1.2</b>
     *
     * <p>对任意随机 parentTools 和 childTools 列表，通过反射调用
     * buildAllowedToolIds，验证结果（排除 infrastructure）⊆ parentTools。</p>
     */
    @Property(tries = 150)
    void subAgent工具集是父Agent子集加Infrastructure(
            @ForAll("randomParentTools") List<String> parentTools,
            @ForAll("randomChildTools") List<String> childTools) throws Exception {

        // 构建 mock registry
        var toolRegistry = mock(DynamicToolRegistry.class);
        when(toolRegistry.getToolSnapshot()).thenReturn(ALL_TOOLS);

        // resolve：已注册工具返回 Optional.of，未注册返回 Optional.empty
        when(toolRegistry.resolve(anyString())).thenAnswer(invocation -> {
            String toolId = invocation.getArgument(0);
            return ALL_TOOLS.stream()
                    .filter(t -> t.id().equals(toolId))
                    .findFirst();
        });

        var agentLoop = mock(AgentLoop.class);
        var config = new MultiAgentProperties();
        config.setMaxDelegationDepth(5);

        var executor = new AgentExecutor(agentLoop, toolRegistry, config);

        // 构造子 Agent 定义（canDelegate=false 避免 handoff 工具干扰）
        AgentDefinition childDef = AgentDefinition.builder()
                .id("test-child")
                .name("Test Child Agent")
                .description("测试子 Agent")
                .systemPrompt("你是测试子 Agent")
                .allowedTools(childTools)
                .canDelegate(false)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .build();

        // 构造父 Agent 状态
        AgentState parentState = AgentState.builder()
                .traceId("parent-trace")
                .sessionId("test-session")
                .goal("父 Agent 目标")
                .phase(AgentPhase.EXECUTING)
                .channel("test")
                .steps(List.of())
                .stepCount(0)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.defaultBudget())
                .depth(0)
                .done(false)
                .allowedToolIds(parentTools)
                .build();

        // 通过反射调用 private buildAllowedToolIds
        Method method = AgentExecutor.class.getDeclaredMethod(
                "buildAllowedToolIds", AgentDefinition.class, AgentState.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(executor, childDef, parentState);

        // 排除 infrastructure 工具后，结果应 ⊆ parentTools
        Set<String> nonInfraResult = result.stream()
                .filter(id -> !INFRA_TOOL_ID_SET.contains(id))
                .collect(Collectors.toSet());

        Set<String> parentToolSet = new HashSet<>(parentTools);

        assertThat(nonInfraResult)
                .as("childTools=%s, parentTools=%s 时，非 infrastructure 结果应 ⊆ parentTools",
                        childTools, parentTools)
                .isSubsetOf(parentToolSet);
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /**
     * 生成随机 parentTools 列表（非空）。
     *
     * <p>从业务工具 + 部分 infrastructure 工具 + 不存在的工具中随机选取。
     * 最小长度为 1，因为空列表在实现中表示"不限制"（不执行 retainAll），
     * 此时子集属性不适用。</p>
     */
    @Provide
    Arbitrary<List<String>> randomParentTools() {
        List<String> candidatePool = new ArrayList<>();
        candidatePool.addAll(BUSINESS_TOOL_IDS);
        candidatePool.addAll(INFRA_TOOL_IDS.subList(0, 5));
        candidatePool.addAll(List.of("nonexistent.tool.1", "nonexistent.tool.2"));

        return Arbitraries.of(candidatePool)
                .list().ofMinSize(1).ofMaxSize(10)
                .uniqueElements()
                .map(List::copyOf);
    }

    /**
     * 生成随机 childTools 列表。
     *
     * <p>从业务工具 + infrastructure 工具 + 不存在的工具中随机选取，
     * 覆盖子 Agent 声明各种工具组合的场景。</p>
     */
    @Provide
    Arbitrary<List<String>> randomChildTools() {
        List<String> candidatePool = new ArrayList<>();
        candidatePool.addAll(BUSINESS_TOOL_IDS);
        candidatePool.addAll(INFRA_TOOL_IDS.subList(0, 8));
        candidatePool.addAll(List.of("nonexistent.tool.3", "fake.tool.xyz"));

        return Arbitraries.of(candidatePool)
                .list().ofMinSize(0).ofMaxSize(12)
                .uniqueElements()
                .map(List::copyOf);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 根据工具 ID 确定风险等级。 */
    private static RiskLevel riskLevelFor(String toolId) {
        if (toolId.contains("shell") || toolId.contains("code")) return RiskLevel.HIGH;
        if (toolId.contains("browser") && !toolId.contains("screenshot")) return RiskLevel.MEDIUM;
        if (toolId.equals("builtin.file.write")) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** 构建测试用 BuiltinTool。 */
    private static BuiltinTool buildTool(String id, List<String> tags, RiskLevel riskLevel) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("测试工具: " + id)
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .tags(tags)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }
}
