package com.lifepilot.meta;

import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.bridge.ToolBridgeAgentToolProvider;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import net.jqwik.api.*;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Infrastructure 工具可见性属性测试。
 *
 * <p>使用 jqwik 生成随机 allowedToolIds 列表，验证：
 * 对于任意 Agent 的 allowedToolIds 白名单配置，所有 tags 含 "infrastructure"
 * 的工具在 ToolBridgeAgentToolProvider 过滤后的结果集中始终存在。</p>
 *
 * <p><b>Validates: Requirements 1.2, Property 1: Infrastructure 工具始终可见</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class InfrastructureToolVisibilityPropertyTest {

    /** Infrastructure 工具 ID 列表 — 模拟系统中注册的基础工具。 */
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

    // ─────────────────────────────────────────────
    //  属性测试 — Infrastructure 工具始终可见
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 1.2</b>
     *
     * <p>对任意随机 allowedToolIds 列表（可能包含业务工具、不存在的工具、
     * 甚至 infrastructure 工具 ID），getToolCallbacks 结果中始终包含
     * 所有 infrastructure 工具。</p>
     */
    @Property(tries = 150)
    void infrastructure工具始终可见_任意allowedToolIds(
            @ForAll("randomAllowedToolIds") List<String> allowedToolIds) {

        // 构建 mock registry，返回固定的工具快照
        var toolRegistry = mock(DynamicToolRegistry.class);
        var pipeline = mock(ToolExecutionPipeline.class);

        List<ToolContract> allTools = buildAllTools();
        when(toolRegistry.getToolSnapshot()).thenReturn(allTools);

        var provider = new ToolBridgeAgentToolProvider(toolRegistry, pipeline, null);

        // 构造 AgentState，白名单为随机生成的 allowedToolIds
        AgentState state = buildState(allowedToolIds);

        // 执行
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        // 收集返回的工具 ID
        Set<String> returnedToolIds = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());

        // 断言：所有 infrastructure 工具都在结果中
        assertThat(returnedToolIds)
                .as("allowedToolIds=%s 时，所有 infrastructure 工具应始终可见", allowedToolIds)
                .containsAll(INFRA_TOOL_IDS);
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /**
     * 生成随机 allowedToolIds 列表。
     *
     * <p>从业务工具 + 部分 infrastructure 工具 + 不存在的工具 ID 中随机选取，
     * 确保覆盖各种边界场景：纯业务工具、混合工具、不存在的工具等。</p>
     */
    @Provide
    Arbitrary<List<String>> randomAllowedToolIds() {
        // 候选池：业务工具 + 部分 infrastructure 工具 + 不存在的工具
        List<String> candidatePool = new ArrayList<>();
        candidatePool.addAll(BUSINESS_TOOL_IDS);
        candidatePool.addAll(INFRA_TOOL_IDS.subList(0, 5)); // 部分 infra 工具
        candidatePool.addAll(List.of("nonexistent.tool.1", "nonexistent.tool.2", "fake.tool"));

        return Arbitraries.of(candidatePool)
                .list().ofMinSize(1).ofMaxSize(10)
                .uniqueElements()
                .map(List::copyOf);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建所有工具（infrastructure + 业务工具）。 */
    private List<ToolContract> buildAllTools() {
        List<ToolContract> tools = new ArrayList<>();

        // 注册所有 infrastructure 工具
        for (String id : INFRA_TOOL_IDS) {
            tools.add(buildTool(id, List.of("infrastructure"), riskLevelFor(id)));
        }

        // 注册业务工具
        for (String id : BUSINESS_TOOL_IDS) {
            tools.add(buildTool(id, List.of("skill"), RiskLevel.LOW));
        }

        return List.copyOf(tools);
    }

    /** 根据工具 ID 确定风险等级。 */
    private RiskLevel riskLevelFor(String toolId) {
        if (toolId.contains("shell") || toolId.contains("code")) return RiskLevel.HIGH;
        if (toolId.contains("browser") && !toolId.contains("screenshot")) return RiskLevel.MEDIUM;
        if (toolId.equals("builtin.file.write")) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** 构建测试用 BuiltinTool。 */
    private BuiltinTool buildTool(String id, List<String> tags, RiskLevel riskLevel) {
        return BuiltinTool.builder()
                .id(id)
                .name(id)
                .description("测试工具: " + id)
                .riskLevel(riskLevel)
                .tags(tags)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    /** 构建测试用 AgentState。 */
    private AgentState buildState(List<String> allowedToolIds) {
        return AgentState.builder()
                .traceId("test-trace")
                .sessionId("test-session")
                .goal("测试目标")
                .phase(AgentPhase.UNDERSTANDING)
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
                .allowedToolIds(allowedToolIds)
                .build();
    }
}
