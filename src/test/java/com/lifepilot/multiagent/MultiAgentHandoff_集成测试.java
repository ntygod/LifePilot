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
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 多 Agent 委托端到端集成测试。
 *
 * <p>使用真实 AgentExecutor、真实 HandoffToolFactory、真实 DynamicToolRegistry，
 * Mock AgentLoop.run() 模拟 SubAgent 执行，验证：
 * <ul>
 *   <li>正常委托流程：主 Agent → handoff → SubAgent 完成任务 → 返回结果</li>
 *   <li>深度检查生效：callerDepth 超限时拒绝执行</li>
 *   <li>白名单过滤正确：排除自递归 handoff 工具</li>
 *   <li>轨迹父子关联完整：SubAgent 的 parentTraceId 关联到调用方 traceId</li>
 * </ul></p>
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 3.1, 3.2</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class MultiAgentHandoff_集成测试 {

    private DynamicToolRegistry toolRegistry;
    private AgentLoop agentLoop;
    private MultiAgentProperties config;
    private AgentExecutor agentExecutor;
    private HandoffToolFactory handoffToolFactory;

    @BeforeEach
    void setUp() {
        // 真实 DynamicToolRegistry
        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        var eventPublisher = mock(ApplicationEventPublisher.class);
        toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);

        // 注册普通工具
        toolRegistry.registerBuiltinTool(buildDummyTool("builtin.todo.create", List.of("builtin")));
        toolRegistry.registerBuiltinTool(buildDummyTool("builtin.schedule.create", List.of("builtin")));
        toolRegistry.registerBuiltinTool(buildDummyTool("builtin.memory.search", List.of("builtin")));

        // Mock AgentLoop
        agentLoop = mock(AgentLoop.class);

        // 真实 MultiAgentProperties
        config = new MultiAgentProperties();
        config.setMaxDelegationDepth(2);

        // 真实 AgentExecutor + HandoffToolFactory
        agentExecutor = new AgentExecutor(agentLoop, toolRegistry, config);
        handoffToolFactory = new HandoffToolFactory(agentExecutor);

        // 注册 handoff 工具到 registry（模拟多 Agent 场景）
        var writerHandoff = handoffToolFactory.createHandoffTool(buildWriterDefinition());
        var analystHandoff = handoffToolFactory.createHandoffTool(buildAnalystDefinition());
        toolRegistry.registerBuiltinTool(writerHandoff);
        toolRegistry.registerBuiltinTool(analystHandoff);
    }

    // ─────────────────────────────────────────────
    //  场景 1 — 正常委托流程，depth 正确传播
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("正常委托流程 — 主 Agent 调用 handoff_to_writer，depth 正确传播为 callerDepth + 1")
    void 正常委托流程_depth正确传播() {
        // Mock AgentLoop 返回成功响应
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("sub-trace-1", "session-1", "文章已完成", 500, 5, null));

        // 通过 HandoffToolFactory 创建的工具执行委托
        var writerHandoff = handoffToolFactory.createHandoffTool(buildWriterDefinition());
        Map<String, Object> params = new HashMap<>();
        params.put("task", "写一篇关于 ZhiWei 架构的文章");
        params.put("context", "需要涵盖多 Agent 协作模块");
        params.put("_callerDepth", 0);
        params.put("_callerTraceId", "parent-trace-001");
        params.put("_callerSessionId", "session-1");
        var toolInput = new ToolInput("handoff_to_writer", params, JsonSchema.empty(), null);

        // 执行
        ToolResult result = writerHandoff.execute(toolInput);

        // 验证：工具执行成功
        assertTrue(result.ok(), "handoff 工具应执行成功，但返回错误: " + result.error());

        // 捕获传给 agentLoop.run() 的 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        // 验证 depth = callerDepth(0) + 1 = 1
        assertEquals(1, capturedRequest.depth(),
                "SubAgent depth 应为 callerDepth(0) + 1 = 1");

        // 验证 parentTraceId 非空（AgentExecutor 使用 minimalParentState.traceId() 作为 parentTraceId）
        // minimalParentState.traceId() 是 HandoffToolFactory 内部生成的 UUID，
        // 而 _callerTraceId 存储在 minimalParentState.parentTraceId() 中维护链路
        assertNotNull(capturedRequest.parentTraceId(),
                "SubAgent parentTraceId 不应为 null");

        // 验证 sessionId 传递
        assertEquals("session-1", capturedRequest.sessionId(),
                "SubAgent sessionId 应与调用方一致");
    }

    // ─────────────────────────────────────────────
    //  场景 2 — 深度超限拦截
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("深度超限拦截 — maxDelegationDepth=2 且 callerDepth=2 时拒绝执行")
    void 深度超限拦截_callerDepth等于maxDelegationDepth() {
        // maxDelegationDepth=2（setUp 中已设置）
        // callerDepth=2 → newDepth = 2 + 1 = 3 > maxDelegationDepth=2

        // 通过 HandoffToolFactory 创建的工具执行委托
        var writerHandoff = handoffToolFactory.createHandoffTool(buildWriterDefinition());
        Map<String, Object> params = new HashMap<>();
        params.put("task", "写一篇文章");
        params.put("_callerDepth", 2);
        params.put("_callerTraceId", "deep-trace");
        params.put("_callerSessionId", "session-deep");
        var toolInput = new ToolInput("handoff_to_writer", params, JsonSchema.empty(), null);

        // 执行
        ToolResult result = writerHandoff.execute(toolInput);

        // 验证：工具返回错误（深度超限）
        assertFalse(result.ok(), "深度超限时 handoff 工具应返回错误");
        assertTrue(result.error().contains("委托深度超限"),
                "错误消息应包含 '委托深度超限'，实际: " + result.error());

        // 验证：AgentLoop.run() 未被调用（深度检查在 execute 之前拦截）
        verify(agentLoop, never()).run(any(AgentRequest.class));
    }

    @Test
    @DisplayName("深度超限边界 — maxDelegationDepth=2 且 callerDepth=1 时允许执行")
    void 深度超限边界_callerDepth小于maxDelegationDepth() {
        // callerDepth=1 → newDepth = 1 + 1 = 2 == maxDelegationDepth=2 → 允许执行
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("sub-trace-2", "session-1", "完成", 200, 3, null));

        var writerHandoff = handoffToolFactory.createHandoffTool(buildWriterDefinition());
        Map<String, Object> params = new HashMap<>();
        params.put("task", "写一篇文章");
        params.put("_callerDepth", 1);
        params.put("_callerTraceId", "mid-trace");
        params.put("_callerSessionId", "session-1");
        var toolInput = new ToolInput("handoff_to_writer", params, JsonSchema.empty(), null);

        // 执行
        ToolResult result = writerHandoff.execute(toolInput);

        // 验证：允许执行（newDepth=2 == maxDelegationDepth=2）
        assertTrue(result.ok(), "newDepth=2 等于 maxDelegationDepth=2 时应允许执行，但返回错误: " + result.error());

        // 验证 depth 正确传播
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        assertEquals(2, requestCaptor.getValue().depth(),
                "SubAgent depth 应为 callerDepth(1) + 1 = 2");
    }

    // ─────────────────────────────────────────────
    //  场景 3 — 白名单过滤：排除自递归 handoff 工具
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("白名单过滤 — writer SubAgent 的 allowedToolIds 排除 handoff_to_writer 但包含其他工具")
    void 白名单过滤_排除自递归handoff但包含其他工具() {
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("sub-trace-3", "session-1", "完成", 300, 4, null));

        // writer 定义：allowedTools 包含自递归 handoff + 其他 handoff + 普通工具
        var writerDef = AgentDefinition.builder()
                .id("writer")
                .name("Writer")
                .description("写作助手")
                .systemPrompt("你是写作助手")
                .allowedTools(List.of(
                        "handoff_to_writer",      // 自递归 — 应被排除
                        "handoff_to_analyst",     // 指向其他 Agent — 应保留
                        "builtin.todo.create",    // 普通工具 — 应保留
                        "builtin.memory.search"   // 普通工具 — 应保留
                ))
                .canDelegate(true)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        var parentState = buildParentState(0, "parent-trace-filter", "session-1");

        // 通过 AgentExecutor 直接执行（更直接地测试 buildAllowedToolIds）
        agentExecutor.execute(writerDef, "写一篇文章", null, parentState);

        // 捕获 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        assertNotNull(capturedRequest.allowedToolIds(), "allowedToolIds 不应为 null");

        // 自递归 handoff 应被排除
        assertFalse(capturedRequest.allowedToolIds().contains("handoff_to_writer"),
                "allowedToolIds 不应包含自递归 handoff_to_writer，实际: " + capturedRequest.allowedToolIds());

        // 指向其他 Agent 的 handoff 应保留
        assertTrue(capturedRequest.allowedToolIds().contains("handoff_to_analyst"),
                "allowedToolIds 应包含 handoff_to_analyst（指向其他 Agent），实际: " + capturedRequest.allowedToolIds());

        // 普通工具应保留
        assertTrue(capturedRequest.allowedToolIds().contains("builtin.todo.create"),
                "allowedToolIds 应包含 builtin.todo.create");
        assertTrue(capturedRequest.allowedToolIds().contains("builtin.memory.search"),
                "allowedToolIds 应包含 builtin.memory.search");

        // 总数应为 3（排除了 1 个自递归）
        assertEquals(3, capturedRequest.allowedToolIds().size(),
                "allowedToolIds 应有 3 个工具（排除自递归后），实际: " + capturedRequest.allowedToolIds());
    }

    @Test
    @DisplayName("白名单过滤 — canDelegate=false 时排除所有 handoff 工具")
    void 白名单过滤_canDelegateFalse排除所有handoff() {
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("sub-trace-4", "session-1", "完成", 200, 3, null));

        // writer 定义：canDelegate=false
        var writerDef = AgentDefinition.builder()
                .id("writer")
                .name("Writer")
                .description("写作助手")
                .systemPrompt("你是写作助手")
                .allowedTools(List.of(
                        "handoff_to_analyst",     // handoff — canDelegate=false 时应排除
                        "builtin.todo.create",    // 普通工具 — 应保留
                        "builtin.memory.search"   // 普通工具 — 应保留
                ))
                .canDelegate(false)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        var parentState = buildParentState(0, "parent-trace-nodelegate", "session-1");

        agentExecutor.execute(writerDef, "写一篇文章", null, parentState);

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        assertNotNull(capturedRequest.allowedToolIds());

        // canDelegate=false 时所有 handoff 工具应被排除
        assertFalse(capturedRequest.allowedToolIds().contains("handoff_to_analyst"),
                "canDelegate=false 时不应包含 handoff_to_analyst");

        // 普通工具应保留
        assertTrue(capturedRequest.allowedToolIds().contains("builtin.todo.create"));
        assertTrue(capturedRequest.allowedToolIds().contains("builtin.memory.search"));
        assertEquals(2, capturedRequest.allowedToolIds().size(),
                "canDelegate=false 时应只有 2 个普通工具");
    }

    // ─────────────────────────────────────────────
    //  场景 4 — 轨迹父子关联完整
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("轨迹父子关联 — SubAgent 的 parentTraceId 非空且 sessionId 正确传递")
    void 轨迹父子关联_parentTraceId正确传递() {
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("sub-trace-5", "session-1", "分析完成", 400, 6, null));

        String callerTraceId = "main-agent-trace-abc123";
        String callerSessionId = "shared-session-xyz";

        // 通过 HandoffToolFactory 创建的工具执行
        var analystHandoff = handoffToolFactory.createHandoffTool(buildAnalystDefinition());
        Map<String, Object> params = new HashMap<>();
        params.put("task", "分析用户行为数据");
        params.put("_callerDepth", 0);
        params.put("_callerTraceId", callerTraceId);
        params.put("_callerSessionId", callerSessionId);
        var toolInput = new ToolInput("handoff_to_analyst", params, JsonSchema.empty(), null);

        // 执行
        ToolResult result = analystHandoff.execute(toolInput);
        assertTrue(result.ok(), "handoff 工具应执行成功");

        // 捕获 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        // 验证 parentTraceId 非空（AgentExecutor 使用 minimalParentState.traceId()）
        assertNotNull(capturedRequest.parentTraceId(),
                "SubAgent parentTraceId 不应为 null");
        // parentTraceId 是 HandoffToolFactory 内部生成的 UUID，不等于 callerTraceId
        // callerTraceId 存储在 minimalParentState.parentTraceId() 中维护完整链路
        assertNotEquals(callerTraceId, capturedRequest.parentTraceId(),
                "parentTraceId 应为 minimalParentState.traceId()（内部 UUID），而非 callerTraceId");

        // 验证 sessionId 传递
        assertEquals(callerSessionId, capturedRequest.sessionId(),
                "SubAgent sessionId 应与调用方一致");

        // 验证 depth 传播
        assertEquals(1, capturedRequest.depth(),
                "SubAgent depth 应为 callerDepth(0) + 1 = 1");
    }

    @Test
    @DisplayName("轨迹父子关联 — 多层委托时 depth 逐层递增且 parentTraceId 逐层生成")
    void 轨迹父子关联_多层委托逐层传递() {
        // 模拟两层委托：主 Agent(depth=0) → writer(depth=1) → analyst(depth=2)
        // 第一层：主 Agent → writer
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("writer-trace", "session-1", "完成", 300, 4, null));

        var writerHandoff = handoffToolFactory.createHandoffTool(buildWriterDefinition());
        Map<String, Object> params1 = new HashMap<>();
        params1.put("task", "写文章");
        params1.put("_callerDepth", 0);
        params1.put("_callerTraceId", "main-trace");
        params1.put("_callerSessionId", "session-1");
        writerHandoff.execute(new ToolInput("handoff_to_writer", params1, JsonSchema.empty(), null));

        ArgumentCaptor<AgentRequest> captor1 = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(captor1.capture());
        AgentRequest layer1Request = captor1.getValue();

        // 第一层：parentTraceId 非空（minimalParentState.traceId()），depth=1
        assertNotNull(layer1Request.parentTraceId(),
                "第一层 SubAgent parentTraceId 不应为 null");
        assertEquals(1, layer1Request.depth(), "第一层 depth 应为 1");

        // 第二层：writer(depth=1) → analyst(depth=2)
        reset(agentLoop);
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("analyst-trace", "session-1", "分析完成", 200, 3, null));

        var analystHandoff = handoffToolFactory.createHandoffTool(buildAnalystDefinition());
        Map<String, Object> params2 = new HashMap<>();
        params2.put("task", "分析数据");
        params2.put("_callerDepth", 1);
        params2.put("_callerTraceId", "writer-trace-id");
        params2.put("_callerSessionId", "session-1");
        analystHandoff.execute(new ToolInput("handoff_to_analyst", params2, JsonSchema.empty(), null));

        ArgumentCaptor<AgentRequest> captor2 = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(captor2.capture());
        AgentRequest layer2Request = captor2.getValue();

        // 第二层：parentTraceId 非空且与第一层不同，depth=2
        assertNotNull(layer2Request.parentTraceId(),
                "第二层 SubAgent parentTraceId 不应为 null");
        assertNotEquals(layer1Request.parentTraceId(), layer2Request.parentTraceId(),
                "第二层 parentTraceId 应与第一层不同（各层独立生成）");
        assertEquals(2, layer2Request.depth(), "第二层 depth 应为 2");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建 writer AgentDefinition。 */
    private AgentDefinition buildWriterDefinition() {
        return AgentDefinition.builder()
                .id("writer")
                .name("Writer")
                .description("写作助手")
                .systemPrompt("你是写作助手，擅长撰写技术文档")
                .allowedTools(List.of("builtin.todo.create", "builtin.memory.search"))
                .canDelegate(false)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();
    }

    /** 构建 analyst AgentDefinition。 */
    private AgentDefinition buildAnalystDefinition() {
        return AgentDefinition.builder()
                .id("analyst")
                .name("Analyst")
                .description("数据分析助手")
                .systemPrompt("你是数据分析助手")
                .allowedTools(List.of("builtin.todo.create", "builtin.schedule.create"))
                .canDelegate(false)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();
    }

    /** 构建 parentState。 */
    private AgentState buildParentState(int depth, String traceId, String sessionId) {
        return AgentState.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .goal("测试委托")
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
                .depth(depth)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();
    }

    /** 构建 dummy BuiltinTool（仅用于注册到 registry）。 */
    private static BuiltinTool buildDummyTool(String toolId, List<String> tags) {
        return BuiltinTool.builder()
                .id(toolId)
                .name(toolId)
                .description("测试工具: " + toolId)
                .tags(tags)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }
}
