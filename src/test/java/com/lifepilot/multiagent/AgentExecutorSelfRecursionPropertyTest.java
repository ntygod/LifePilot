package com.lifepilot.multiagent;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.*;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.execution.HandoffToolFactory;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 自递归防护属性测试。
 *
 * <p>使用 jqwik 生成随机 AgentDefinition（随机 id、随机 allowedTools），验证：
 * <ul>
 *   <li>buildAllowedToolIds() 不包含 "handoff_to_{自身id}"</li>
 *   <li>buildAllowedToolIds() 包含指向其他 Agent 的 handoff 工具（若在 allowedTools 中且已注册）</li>
 * </ul></p>
 *
 * <p><b>Validates: Property 2, Requirements 2.3, 3.2</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class AgentExecutorSelfRecursionPropertyTest {

    /** Agent ID 候选池。 */
    private static final List<String> AGENT_ID_POOL = List.of(
            "writer", "analyst", "researcher", "planner", "coder",
            "reviewer", "translator", "summarizer"
    );

    /** 普通工具 ID 候选池。 */
    private static final List<String> NORMAL_TOOL_POOL = List.of(
            "builtin.todo.create", "builtin.todo.list", "builtin.todo.delete",
            "builtin.schedule.create", "builtin.schedule.list",
            "builtin.habit.create", "builtin.habit.track",
            "builtin.memory.search", "builtin.memory.save"
    );

    // ─────────────────────────────────────────────
    //  属性 2.1 — buildAllowedToolIds 不包含自递归 handoff 工具
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.3</b>
     *
     * <p>对任意 AgentDefinition（canDelegate=true），buildAllowedToolIds() 返回的列表
     * 不应包含 "handoff_to_{自身id}"。</p>
     */
    @Property(tries = 200)
    void buildAllowedToolIds_不包含自递归handoff工具(
            @ForAll("selfRecursionScenarios") SelfRecursionScenario scenario) {

        // 构建 registry 并注册所有工具
        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);

        for (String toolId : scenario.registeredToolIds()) {
            toolRegistry.registerBuiltinTool(buildDummyTool(toolId));
        }

        // 构造 AgentDefinition
        var definition = AgentDefinition.builder()
                .id(scenario.agentId())
                .name("Agent-" + scenario.agentId())
                .description("测试 Agent")
                .systemPrompt("你是测试助手")
                .allowedTools(scenario.allowedTools())
                .canDelegate(true)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        // 通过 execute() 间接测试 buildAllowedToolIds()
        var agentLoop = mock(AgentLoop.class);
        var config = new MultiAgentProperties();
        config.setMaxDelegationDepth(10);
        var agentExecutor = new AgentExecutor(agentLoop, toolRegistry, config);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("trace-1", "session-1", "完成", 100, 3, null));

        var parentState = buildMinimalParentState();

        // 执行
        agentExecutor.execute(definition, "测试任务", null, parentState);

        // 捕获传给 agentLoop.run() 的 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        // 断言：allowedToolIds 不包含指向自身的 handoff 工具
        String selfHandoffId = HandoffToolFactory.TOOL_ID_PREFIX + scenario.agentId();
        assertNotNull(capturedRequest.allowedToolIds(), "allowedToolIds 不应为 null");
        assertFalse(capturedRequest.allowedToolIds().contains(selfHandoffId),
                "buildAllowedToolIds() 不应包含自递归 handoff 工具 '" + selfHandoffId
                        + "'，但实际列表为: " + capturedRequest.allowedToolIds());
    }

    // ─────────────────────────────────────────────
    //  属性 2.2 — buildAllowedToolIds 包含指向其他 Agent 的 handoff 工具
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 3.2</b>
     *
     * <p>对任意 canDelegate=true 的 AgentDefinition，buildAllowedToolIds() 返回的列表
     * 应包含指向其他 Agent 的 handoff 工具（若在 allowedTools 中且已注册）。</p>
     */
    @Property(tries = 200)
    void buildAllowedToolIds_包含指向其他Agent的handoff工具(
            @ForAll("crossAgentHandoffScenarios") CrossAgentHandoffScenario scenario) {

        // 构建 registry 并注册所有工具
        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);

        for (String toolId : scenario.registeredToolIds()) {
            toolRegistry.registerBuiltinTool(buildDummyTool(toolId));
        }

        // 构造 AgentDefinition（canDelegate=true）
        var definition = AgentDefinition.builder()
                .id(scenario.agentId())
                .name("Agent-" + scenario.agentId())
                .description("测试 Agent")
                .systemPrompt("你是测试助手")
                .allowedTools(scenario.allowedTools())
                .canDelegate(true)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        // 通过 execute() 间接测试 buildAllowedToolIds()
        var agentLoop = mock(AgentLoop.class);
        var config = new MultiAgentProperties();
        config.setMaxDelegationDepth(10);
        var agentExecutor = new AgentExecutor(agentLoop, toolRegistry, config);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("trace-1", "session-1", "完成", 100, 3, null));

        var parentState = buildMinimalParentState();

        // 执行
        agentExecutor.execute(definition, "测试任务", null, parentState);

        // 捕获传给 agentLoop.run() 的 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        assertNotNull(capturedRequest.allowedToolIds(), "allowedToolIds 不应为 null");

        // 断言：所有指向其他 Agent 的 handoff 工具（在 allowedTools 中且已注册）应出现在结果中
        for (String expectedOtherHandoff : scenario.expectedOtherHandoffIds()) {
            assertTrue(capturedRequest.allowedToolIds().contains(expectedOtherHandoff),
                    "buildAllowedToolIds() 应包含指向其他 Agent 的 handoff 工具 '"
                            + expectedOtherHandoff + "'，但实际列表为: "
                            + capturedRequest.allowedToolIds());
        }
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /**
     * 生成自递归场景 — Agent 的 allowedTools 包含指向自身的 handoff 工具。
     *
     * <p>确保 allowedTools 中包含 "handoff_to_{agentId}"（自递归），
     * 以及若干普通工具和可能的其他 handoff 工具。</p>
     */
    @Provide
    Arbitrary<SelfRecursionScenario> selfRecursionScenarios() {
        return Arbitraries.of(AGENT_ID_POOL).flatMap(agentId -> {
            String selfHandoffId = HandoffToolFactory.TOOL_ID_PREFIX + agentId;

            // 选取若干普通工具
            Arbitrary<List<String>> normalToolsArb = Arbitraries.of(NORMAL_TOOL_POOL)
                    .list().ofMinSize(1).ofMaxSize(4).uniqueElements();

            // 选取若干指向其他 Agent 的 handoff 工具
            List<String> otherAgentIds = AGENT_ID_POOL.stream()
                    .filter(id -> !id.equals(agentId))
                    .toList();
            Arbitrary<List<String>> otherHandoffsArb = Arbitraries.of(otherAgentIds)
                    .map(id -> HandoffToolFactory.TOOL_ID_PREFIX + id)
                    .list().ofMinSize(0).ofMaxSize(3).uniqueElements();

            return Combinators.combine(normalToolsArb, otherHandoffsArb).as((normalTools, otherHandoffs) -> {
                // allowedTools = 自递归 handoff + 普通工具 + 其他 handoff
                var allowedTools = new ArrayList<String>();
                allowedTools.add(selfHandoffId);
                allowedTools.addAll(normalTools);
                allowedTools.addAll(otherHandoffs);

                // registeredToolIds = 所有 allowedTools 中的工具都注册
                var registeredToolIds = new ArrayList<>(allowedTools);

                return new SelfRecursionScenario(agentId, List.copyOf(allowedTools), List.copyOf(registeredToolIds));
            });
        });
    }

    /**
     * 生成跨 Agent 委托场景 — Agent 的 allowedTools 包含指向其他 Agent 的 handoff 工具。
     *
     * <p>确保至少有一个指向其他 Agent 的 handoff 工具在 allowedTools 中且已注册。</p>
     */
    @Provide
    Arbitrary<CrossAgentHandoffScenario> crossAgentHandoffScenarios() {
        return Arbitraries.of(AGENT_ID_POOL).flatMap(agentId -> {
            // 选取若干普通工具
            Arbitrary<List<String>> normalToolsArb = Arbitraries.of(NORMAL_TOOL_POOL)
                    .list().ofMinSize(1).ofMaxSize(4).uniqueElements();

            // 选取至少 1 个指向其他 Agent 的 handoff 工具
            List<String> otherAgentIds = AGENT_ID_POOL.stream()
                    .filter(id -> !id.equals(agentId))
                    .toList();
            Arbitrary<List<String>> otherHandoffsArb = Arbitraries.of(otherAgentIds)
                    .map(id -> HandoffToolFactory.TOOL_ID_PREFIX + id)
                    .list().ofMinSize(1).ofMaxSize(3).uniqueElements();

            return Combinators.combine(normalToolsArb, otherHandoffsArb).as((normalTools, otherHandoffs) -> {
                // allowedTools = 普通工具 + 其他 handoff（不含自递归）
                var allowedTools = new ArrayList<String>();
                allowedTools.addAll(normalTools);
                allowedTools.addAll(otherHandoffs);

                // registeredToolIds = 所有 allowedTools 中的工具都注册
                var registeredToolIds = new ArrayList<>(allowedTools);

                // expectedOtherHandoffIds = 指向其他 Agent 的 handoff 工具（已注册）
                var expectedOtherHandoffIds = List.copyOf(otherHandoffs);

                return new CrossAgentHandoffScenario(
                        agentId, List.copyOf(allowedTools),
                        List.copyOf(registeredToolIds), expectedOtherHandoffIds);
            });
        });
    }

    // ─────────────────────────────────────────────
    //  辅助方法和数据类
    // ─────────────────────────────────────────────

    /** 自递归场景数据。 */
    record SelfRecursionScenario(
            String agentId,
            List<String> allowedTools,
            List<String> registeredToolIds) {}

    /** 跨 Agent 委托场景数据。 */
    record CrossAgentHandoffScenario(
            String agentId,
            List<String> allowedTools,
            List<String> registeredToolIds,
            List<String> expectedOtherHandoffIds) {}

    /** 构建最小 parentState 用于 execute() 调用。 */
    private static AgentState buildMinimalParentState() {
        return AgentState.builder()
                .traceId("parent-trace")
                .sessionId("session-1")
                .goal("测试")
                .phase(AgentPhase.EXECUTING)
                .channel("cli")
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.defaultBudget())
                .parentTraceId(null)
                .depth(0)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();
    }

    /** 构建 dummy BuiltinTool（仅用于注册到 registry）。 */
    private static BuiltinTool buildDummyTool(String toolId) {
        List<String> tags = toolId.startsWith(HandoffToolFactory.TOOL_ID_PREFIX)
                ? List.of("handoff", "multi-agent")
                : List.of("builtin");
        return BuiltinTool.builder()
                .id(toolId)
                .name(toolId)
                .description("测试工具: " + toolId)
                .tags(tags)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }
}
