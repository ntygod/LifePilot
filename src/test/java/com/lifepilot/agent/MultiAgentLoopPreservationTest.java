package com.lifepilot.agent;

import com.lifepilot.agent.model.*;
import com.lifepilot.interaction.NoOpUserConfirmationService;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.working.BudgetAllocation;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.IdempotencyManager;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 多 Agent 循环保持性测试 — 验证非 Bug 路径行为在修复后不被破坏。
 *
 * <p>这些测试在未修复代码上运行，预期全部通过 — 通过确认基线行为。
 * 修复后这些测试仍应全部通过（保持性验证）。</p>
 *
 * <p>覆盖 4 个保持性属性：
 * <ul>
 *   <li>Property 5：普通工具超时重试策略不变</li>
 *   <li>Property 2（保持部分）：跨 Agent 委托白名单包含指向其他 Agent 的 handoff 工具</li>
 *   <li>Property 10：有数据时 TokenBudgetAllocator 预算分配策略不变</li>
 *   <li>Property 11：StateReducer toBuilder() 保持所有现有字段</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class MultiAgentLoopPreservationTest {

    private DynamicToolRegistry toolRegistry;
    private GuardrailEngine guardrailEngine;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        eventPublisher = mock(ApplicationEventPublisher.class);
        toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);
    }

    // ─────────────────────────────────────────────
    //  测试 2.1 — 普通工具重试保持（Property 5）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("2.1 普通工具重试保持 — tags 不含 handoff 的工具超时后应被重试")
    void 普通工具重试保持_超时后应被重试() {
        // 准备：创建 tags 不含 "handoff" 的普通工具，始终返回超时错误，并计数调用次数
        var callCount = new int[]{0};
        var normalTool = BuiltinTool.builder()
                .id("builtin.todo.create")
                .name("创建待办")
                .description("创建待办事项")
                .tags(List.of("builtin"))
                .idempotent(false)
                .executor(input -> {
                    callCount[0]++;
                    return ToolResult.error("执行超时: 30秒");
                })
                .build();
        toolRegistry.registerBuiltinTool(normalTool);

        // 构造 pipeline，maxRetries=2（默认 ToolBudget.DEFAULT）
        var pipeline = new ToolExecutionPipeline(
                toolRegistry, guardrailEngine,
                new IdempotencyManager(),
                new NoOpUserConfirmationService(),
                100, 2.0, 1000);

        // 执行
        pipeline.execute("builtin.todo.create", Map.of(), "trace-1", null);

        // 断言：普通工具应被重试 — 1 次初始 + 2 次重试 = 3 次调用
        // **Validates: Requirements 3.3**
        assertEquals(3, callCount[0],
                "期望普通工具（tags 不含 handoff）超时后被重试 3 次（1 初始 + 2 重试），"
                        + "但实际执行了 " + callCount[0] + " 次");
    }

    // ─────────────────────────────────────────────
    //  测试 2.2 — 跨 Agent 委托保持（Property 2 保持部分）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("2.2 跨 Agent 委托保持 — canDelegate=true 时白名单包含指向其他 Agent 的 handoff 工具")
    void 跨Agent委托保持_白名单包含其他Agent的handoff工具() {
        // 准备：注册 handoff_to_analyst（指向其他 Agent）和 builtin.todo.create
        var handoffToAnalyst = BuiltinTool.builder()
                .id("handoff_to_analyst")
                .name("analyst")
                .description("委托给分析助手")
                .tags(List.of("handoff", "multi-agent"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        var todoTool = BuiltinTool.builder()
                .id("builtin.todo.create")
                .name("创建待办")
                .description("创建待办事项")
                .tags(List.of("builtin"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        toolRegistry.registerBuiltinTool(handoffToAnalyst);
        toolRegistry.registerBuiltinTool(todoTool);

        // 构造 writer Agent 定义（canDelegate=true，allowedTools 包含指向其他 Agent 的 handoff）
        var definition = AgentDefinition.builder()
                .id("writer")
                .name("Writer")
                .description("写作助手")
                .systemPrompt("你是写作助手")
                .allowedTools(List.of("handoff_to_analyst", "builtin.todo.create"))
                .canDelegate(true)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        // 通过 AgentExecutor 间接测试 buildAllowedToolIds
        var agentLoop = mock(AgentLoop.class);
        var config = new MultiAgentProperties();
        var agentExecutor = new AgentExecutor(agentLoop, toolRegistry, config);

        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("trace-1", "session-1", "完成", 100, 3, null));

        // 构造 parentState
        var parentState = AgentState.builder()
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

        // 执行
        agentExecutor.execute(definition, "写一篇文章", null, parentState);

        // 捕获传给 agentLoop.run() 的 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        // 断言：canDelegate=true 时，白名单应包含指向其他 Agent 的 handoff 工具
        // **Validates: Requirements 3.2**
        assertNotNull(capturedRequest.allowedToolIds(), "allowedToolIds 不应为 null");
        assertTrue(capturedRequest.allowedToolIds().contains("handoff_to_analyst"),
                "期望 allowedToolIds 包含 handoff_to_analyst（指向其他 Agent），但实际: "
                        + capturedRequest.allowedToolIds());
        assertTrue(capturedRequest.allowedToolIds().contains("builtin.todo.create"),
                "期望 allowedToolIds 包含 builtin.todo.create");
    }

    // ─────────────────────────────────────────────
    //  测试 2.3 — 有数据时预算分配保持（Property 10）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("2.3 有数据时预算分配保持 — allocate(16000, 5, 0.8f) 返回合理预算分配")
    void 有数据时预算分配保持_返回合理预算分配() {
        // 准备：使用默认 MemoryProperties 构造 TokenBudgetAllocator
        var properties = new MemoryProperties();
        var allocator = new TokenBudgetAllocator(properties);

        // 执行：典型有数据场景
        BudgetAllocation allocation = allocator.allocate(16000, 5, 0.8f, true);

        // 断言：验证预算分配的基本约束
        // **Validates: Requirements 3.4**

        // 系统提示词区 = 16000 * 0.10 = 1600
        assertTrue(allocation.systemPromptBudget() > 0,
                "系统提示词预算应大于 0，实际: " + allocation.systemPromptBudget());
        assertEquals(1600, allocation.systemPromptBudget(),
                "系统提示词预算应为 1600（16000 * 0.10）");

        // 用户消息区 = 16000 * 0.15 = 2400
        assertTrue(allocation.userMessageBudget() > 0,
                "用户消息预算应大于 0，实际: " + allocation.userMessageBudget());
        assertEquals(2400, allocation.userMessageBudget(),
                "用户消息预算应为 2400（16000 * 0.15）");

        // 所有记忆区域预算非负
        assertTrue(allocation.userProfileBudget() >= 0, "用户画像预算不应为负");
        assertTrue(allocation.currentSessionBudget() >= 0, "当前会话预算不应为负");
        assertTrue(allocation.crossSessionBudget() >= 0, "跨会话预算不应为负");
        assertTrue(allocation.knowledgeEntityBudget() >= 0, "知识实体预算不应为负");
        assertTrue(allocation.proceduralBudget() >= 0, "操作模板预算不应为负");
        assertTrue(allocation.knowledgeBaseBudget() >= 0, "知识库预算不应为负");

        // 总预算等于上下文窗口大小
        assertEquals(16000, allocation.totalBudget(),
                "总预算应等于上下文窗口大小 16000");

        // 所有区域之和不超过总预算（BudgetAllocation 紧凑构造器已校验，此处双重确认）
        int sum = allocation.userProfileBudget() + allocation.currentSessionBudget()
                + allocation.crossSessionBudget() + allocation.knowledgeEntityBudget()
                + allocation.proceduralBudget() + allocation.knowledgeBaseBudget()
                + allocation.systemPromptBudget() + allocation.userMessageBudget();
        assertTrue(sum <= allocation.totalBudget(),
                "所有区域预算之和 " + sum + " 不应超过总预算 " + allocation.totalBudget());
    }

    // ─────────────────────────────────────────────
    //  测试 2.4 — StateReducer 传递现有字段（Property 11）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("2.4 StateReducer 传递现有字段 — reduce 后 toBuilder() 保持所有不变字段")
    void StateReducer传递现有字段_reduce后保持所有不变字段() {
        // 准备：构造一个包含所有字段已知值的 AgentState
        var state = AgentState.builder()
                .traceId("trace-abc-123")
                .sessionId("session-xyz-789")
                .goal("帮我总结架构设计")
                .phase(AgentPhase.UNDERSTANDING)
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of("记忆片段1"))
                .mentionedEntities(List.of("entity-1", "entity-2"))
                .budget(Budget.defaultBudget())
                .parentTraceId("parent-trace-456")
                .depth(2)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();

        // 执行：通过 StateReducer 应用 IntentUnderstood 动作（MODERATE → PLANNING）
        var reducer = new StateReducer();
        var action = new Action.IntentUnderstood(
                "用户想了解架构设计",
                false,
                null,
                true,
                List.of("架构设计"),
                TaskComplexity.MODERATE
        );
        AgentState newState = reducer.reduce(state, action);

        // 断言：不应被 reduce 修改的字段保持不变
        // **Validates: Requirements 3.6**
        assertEquals("trace-abc-123", newState.traceId(),
                "traceId 应在 reduce 后保持不变");
        assertEquals("session-xyz-789", newState.sessionId(),
                "sessionId 应在 reduce 后保持不变");
        assertEquals("web", newState.channel(),
                "channel 应在 reduce 后保持不变");
        assertEquals("parent-trace-456", newState.parentTraceId(),
                "parentTraceId 应在 reduce 后保持不变");
        assertEquals(2, newState.depth(),
                "depth 应在 reduce 后保持不变");
        assertSame(state.budget(), newState.budget(),
                "budget 应在 reduce 后保持同一实例");
        assertFalse(newState.done(),
                "done 应在 reduce 后保持 false");
        assertNull(newState.finalOutput(),
                "finalOutput 应在 reduce 后保持 null");
        assertNull(newState.terminationReason(),
                "terminationReason 应在 reduce 后保持 null");

        // 验证 reduce 确实改变了应该改变的字段
        assertEquals(AgentPhase.PLANNING, newState.phase(),
                "phase 应从 UNDERSTANDING 转换为 PLANNING");
        assertEquals(1, newState.stepCount(),
                "stepCount 应递增为 1");
        assertEquals(1, newState.steps().size(),
                "steps 应新增一条记录");

        // 验证 mentionedEntities 被合并（原有 + 新增）
        assertTrue(newState.mentionedEntities().contains("entity-1"),
                "mentionedEntities 应保留原有实体 entity-1");
        assertTrue(newState.mentionedEntities().contains("entity-2"),
                "mentionedEntities 应保留原有实体 entity-2");
        assertTrue(newState.mentionedEntities().contains("架构设计"),
                "mentionedEntities 应包含新增实体 架构设计");
    }
}
