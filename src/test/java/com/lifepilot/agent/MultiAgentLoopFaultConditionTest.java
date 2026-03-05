package com.lifepilot.agent;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.MemoryRetrievalStrategy;
import com.lifepilot.agent.context.RetrievalStrategyConfig;
import com.lifepilot.agent.model.*;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.BudgetAllocation;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.execution.HandoffToolFactory;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.interaction.NoOpUserConfirmationService;
import com.lifepilot.tool.pipeline.IdempotencyManager;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 多 Agent 循环系统性缺陷探索性测试。
 *
 * <p>这些测试在未修复代码上运行，预期全部失败 — 失败确认 bug 存在。
 * 修复后这些测试应全部通过。</p>
 *
 * <p>覆盖 3 个系统性根因：
 * <ul>
 *   <li>根因 A：跨 Agent 边界的上下文传播链断裂</li>
 *   <li>根因 B：ToolExecutionPipeline 对工具语义无感知</li>
 *   <li>根因 C：记忆/检索系统缺少短路和缓存机制</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class MultiAgentLoopFaultConditionTest {

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
    //  测试 1.1 — AgentState 白名单丢失（根因 A）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("1.1 AgentState 白名单丢失 — ToolBridgeAgentToolProvider 返回全量工具而非白名单子集")
    void AgentState白名单丢失_ToolBridgeAgentToolProvider返回全量工具() {
        // 准备：注册 3 个工具到 registry
        var todoTool = BuiltinTool.builder()
                .id("builtin.todo.create")
                .name("创建待办")
                .description("创建待办事项")
                .tags(List.of("builtin"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        var scheduleTool = BuiltinTool.builder()
                .id("builtin.schedule.create")
                .name("创建日程")
                .description("创建日程安排")
                .tags(List.of("builtin"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        var handoffTool = BuiltinTool.builder()
                .id("handoff_to_writer")
                .name("writer")
                .description("委托给写作助手")
                .tags(List.of("handoff", "multi-agent"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        toolRegistry.registerBuiltinTool(todoTool);
        toolRegistry.registerBuiltinTool(scheduleTool);
        toolRegistry.registerBuiltinTool(handoffTool);

        // 构造携带 allowedToolIds 的 AgentRequest — 只允许 todo 工具
        var request = new AgentRequest(
                "帮我创建一个待办", "session-1", "cli",
                null, null, null, 0, null,
                List.of("builtin.todo.create"),  // 白名单：仅 todo
                null);

        // 初始化 AgentState
        AgentState state = AgentState.init(request);

        // 构造 ToolBridgeAgentToolProvider
        var pipeline = mock(ToolExecutionPipeline.class);
        var provider = new com.lifepilot.tool.bridge.ToolBridgeAgentToolProvider(toolRegistry, pipeline);

        // 执行：获取工具列表
        var callbacks = provider.getToolCallbacks(state);

        // 断言：期望只返回白名单中的 1 个工具，而非全部 3 个
        // 当前代码 BUG：返回全部 3 个工具（不读取 state 的白名单）
        assertEquals(1, callbacks.size(),
                "期望 ToolBridgeAgentToolProvider 按白名单过滤，仅返回 1 个工具，但实际返回了 " + callbacks.size() + " 个");
    }

    // ─────────────────────────────────────────────
    //  测试 1.2 — HandoffToolFactory 深度硬编码（根因 A）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("1.2 HandoffToolFactory 深度硬编码 — minimalParentState.depth 应为 callerDepth 而非 0")
    void HandoffToolFactory深度硬编码_depth应为callerDepth() {
        // 准备：Mock AgentExecutor 以捕获传入的 parentState
        var agentLoop = mock(AgentLoop.class);
        var config = new MultiAgentProperties();
        config.setMaxDelegationDepth(5); // 设置足够大的深度限制，避免深度检查拦截
        var agentExecutor = new AgentExecutor(agentLoop, toolRegistry, config);
        var factory = new HandoffToolFactory(agentExecutor);

        // 构造 AgentDefinition
        // 先注册一个 dummy 工具让 buildAllowedToolIds 不为空
        var dummyTool = BuiltinTool.builder()
                .id("builtin.todo.create")
                .name("创建待办")
                .description("创建待办事项")
                .tags(List.of("builtin"))
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        toolRegistry.registerBuiltinTool(dummyTool);

        var definition = AgentDefinition.builder()
                .id("writer")
                .name("Writer")
                .description("写作助手")
                .systemPrompt("你是写作助手")
                .allowedTools(List.of("builtin.todo.create"))
                .canDelegate(false)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        // 创建 handoff 工具
        BuiltinTool handoffTool = factory.createHandoffTool(definition);

        // Mock agentLoop.run() 返回一个成功响应
        when(agentLoop.run(any(AgentRequest.class))).thenReturn(
                new AgentResponse("trace-1", "session-1", "完成", 100, 3, null));

        // 构造 ToolInput，注入 _callerDepth=2
        Map<String, Object> params = new HashMap<>();
        params.put("task", "写一篇文章");
        params.put("_callerDepth", 2);
        params.put("_callerTraceId", "parent-trace-123");
        params.put("_callerSessionId", "parent-session-456");
        var toolInput = new ToolInput("handoff_to_writer", params, JsonSchema.empty(), null);

        // 执行 handoff 工具
        handoffTool.execute(toolInput);

        // 捕获传给 agentLoop.run() 的 AgentRequest
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentLoop).run(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();

        // 断言：期望 depth = callerDepth + 1 = 3（因为 AgentExecutor 会 +1）
        // 当前代码 BUG：HandoffToolFactory 硬编码 depth(0)，所以 AgentExecutor 计算 newDepth = 0 + 1 = 1
        assertEquals(3, capturedRequest.depth(),
                "期望 depth = callerDepth(2) + 1 = 3，但实际为 " + capturedRequest.depth()
                        + "（HandoffToolFactory 硬编码 depth=0 导致 newDepth=1）");
    }

    // ─────────────────────────────────────────────
    //  测试 1.3 — 自递归防护缺失（根因 A）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("1.3 自递归防护缺失 — buildAllowedToolIds 应排除 handoff_to_{自身id}")
    void 自递归防护缺失_buildAllowedToolIds应排除自身handoff() {
        // 准备：注册 handoff_to_writer 和 builtin.todo.create 两个工具
        var handoffTool = BuiltinTool.builder()
                .id("handoff_to_writer")
                .name("writer")
                .description("委托给写作助手")
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
        toolRegistry.registerBuiltinTool(handoffTool);
        toolRegistry.registerBuiltinTool(todoTool);

        // 构造 writer Agent 定义（canDelegate=true，allowedTools 包含指向自身的 handoff）
        var definition = AgentDefinition.builder()
                .id("writer")
                .name("Writer")
                .description("写作助手")
                .systemPrompt("你是写作助手")
                .allowedTools(List.of("handoff_to_writer", "builtin.todo.create"))
                .canDelegate(true)
                .budget(AgentBudget.DEFAULT)
                .source(new AgentSource.Builtin())
                .metadata(Map.of())
                .build();

        // 通过 AgentExecutor 测试 buildAllowedToolIds 的行为
        // buildAllowedToolIds 是 private，通过 execute() 间接测试：
        // Mock AgentLoop 捕获传入的 AgentRequest.allowedToolIds
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

        // 断言：allowedToolIds 不应包含 handoff_to_writer（自递归）
        // 当前代码 BUG：canDelegate=true 时不排除自递归，列表包含 handoff_to_writer
        assertNotNull(capturedRequest.allowedToolIds(), "allowedToolIds 不应为 null");
        assertFalse(capturedRequest.allowedToolIds().contains("handoff_to_writer"),
                "期望 allowedToolIds 不包含 handoff_to_writer（自递归），但实际包含: "
                        + capturedRequest.allowedToolIds());
        assertTrue(capturedRequest.allowedToolIds().contains("builtin.todo.create"),
                "期望 allowedToolIds 包含 builtin.todo.create");
    }

    // ─────────────────────────────────────────────
    //  测试 1.4 — handoff 工具可重试（根因 B）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("1.4 handoff 工具可重试 — isRetryable 应对 handoff 工具返回 false")
    void handoff工具可重试_isRetryable应返回false() {
        // 准备：创建一个 tags 包含 "handoff" 的工具，始终返回超时错误，并计数调用次数
        var callCount = new int[]{0};
        var handoffTool = BuiltinTool.builder()
                .id("handoff_to_writer")
                .name("writer")
                .description("委托给写作助手")
                .tags(List.of("handoff", "multi-agent"))
                .idempotent(false)
                .executor(input -> {
                    callCount[0]++;
                    return ToolResult.error("执行超时: 30秒");
                })
                .build();
        toolRegistry.registerBuiltinTool(handoffTool);

        // 构造 pipeline，maxRetries=2
        var pipeline = new ToolExecutionPipeline(
                toolRegistry, guardrailEngine,
                new IdempotencyManager(),
                new NoOpUserConfirmationService(),
                100, 2.0, 1000);

        // 执行
        pipeline.execute("handoff_to_writer", Map.of("task", "写一篇文章"), "trace-1", null);

        // 断言：handoff 工具应只被调用 1 次（不重试）
        // 当前代码 BUG：isRetryable() 匹配 "超时" 返回 true，导致重试
        // maxRetries=2 意味着最多调用 3 次（1 次初始 + 2 次重试）
        assertEquals(1, callCount[0],
                "期望 handoff 工具只执行 1 次（不重试），但实际执行了 " + callCount[0] + " 次"
                        + "（isRetryable 未排除 handoff 工具，导致超时后自动重试）");
    }

    // ─────────────────────────────────────────────
    //  测试 1.5 — FTS5 中文查询异常（根因 C）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("1.5 FTS5 中文查询异常 — 包含冒号的中文查询不应抛异常")
    void FTS5中文查询异常_包含冒号的查询不应抛异常() throws Exception {
        // 准备：创建内存 SQLite 数据库并建立 FTS5 表
        // 使用 SingleConnectionDataSource 确保同一连接（内存数据库在连接关闭后丢失）
        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);
        var jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);

        try {
            // 创建必要的表结构
            jdbcTemplate.execute(
                    "CREATE TABLE conversations ("
                            + "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, goal TEXT, summary TEXT, "
                            + "turn_count INTEGER DEFAULT 0, total_tokens INTEGER DEFAULT 0, "
                            + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            jdbcTemplate.execute(
                    "CREATE TABLE messages ("
                            + "id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, role TEXT NOT NULL, "
                            + "content TEXT, compressed_content TEXT, compression_level INTEGER DEFAULT 0, "
                            + "is_pinned INTEGER DEFAULT 0, tool_call_json TEXT, "
                            + "token_count INTEGER DEFAULT 0, created_at TEXT NOT NULL)");
            jdbcTemplate.execute(
                    "CREATE VIRTUAL TABLE messages_fts USING fts5(content)");

            // 插入测试数据
            jdbcTemplate.update(
                    "INSERT INTO conversations (id, session_id, goal, summary, turn_count, total_tokens, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "conv-1", "session-1", "测试", "测试对话", 1, 100,
                    "2026-03-05T10:00:00Z", "2026-03-05T10:00:00Z");
            jdbcTemplate.update(
                    "INSERT INTO messages (id, conversation_id, role, content, compression_level, is_pinned, token_count, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "msg-1", "conv-1", "user", "帮我总结一下架构设计", 0, 0, 10,
                    "2026-03-05T10:00:00Z");
            // 手动同步 FTS5 索引（使用 messages 表的 rowid）
            jdbcTemplate.execute(
                    "INSERT INTO messages_fts(rowid, content) SELECT rowid, content FROM messages");

            // 构造 EpisodicMemory
            var episodicMemory = new com.lifepilot.memory.episodic.EpisodicMemory(jdbcTemplate);

            // 执行：使用包含冒号的中文查询（FTS5 特殊字符）
            // 当前代码 BUG：冒号被 FTS5 解析为列限定符，触发 SQL 语法错误
            // EpisodicMemory.searchExcludingSession 内部 catch 了异常并返回空列表
            // 所以我们需要验证查询实际上能返回匹配结果（而非因异常返回空列表）
            var results = episodicMemory.searchExcludingSession(
                    "帮我总结一下:架构设计", "session-other", 10);

            // 断言：查询应返回匹配结果（msg-1 包含 "架构设计"）
            // 当前代码 BUG：FTS5 MATCH 未转义冒号，抛出异常后被 catch 返回空列表
            assertFalse(results.isEmpty(),
                    "期望 FTS5 查询 '帮我总结一下:架构设计' 返回匹配结果，但返回了空列表"
                            + "（冒号被 FTS5 解析为列限定符导致查询失败，异常被 catch 后返回空列表）");
        } finally {
            dataSource.destroy();
        }
    }

    // ─────────────────────────────────────────────
    //  测试 1.6 — 降级误报（根因 C）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("1.6 降级误报 — 检索返回空时 degraded 应为 false")
    void 降级误报_检索返回空时degraded应为false() {
        // 准备：构造完整版 ContextAssembler，Mock 所有依赖
        var agentConfig = new AgentConfigProperties();

        // Mock HybridRetriever 返回空列表
        var hybridRetriever = mock(HybridRetriever.class);
        when(hybridRetriever.retrieve(anyString(), anyInt(), any(RetrievalWeights.class)))
                .thenReturn(List.of());

        // Mock WorkingMemory 返回空列表
        var workingMemory = mock(WorkingMemory.class);
        when(workingMemory.getContext(anyString())).thenReturn(List.of());

        // Mock TokenBudgetAllocator 返回默认分配
        var tokenBudgetAllocator = mock(TokenBudgetAllocator.class);
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat()))
                .thenReturn(new BudgetAllocation(
                        500, 3000, 1000, 1500, 500, 500,
                        3200, 4800, 16000));

        // Mock MemoryRetrievalStrategy 返回非 skip 配置
        var retrievalStrategy = mock(MemoryRetrievalStrategy.class);
        when(retrievalStrategy.getStrategy(any(AgentPhase.class)))
                .thenReturn(new RetrievalStrategyConfig(5, RetrievalWeights.DEFAULT, false, false));

        // 构造 ContextAssembler（完整版）
        var assembler = new ContextAssembler(
                agentConfig, hybridRetriever, workingMemory,
                tokenBudgetAllocator, retrievalStrategy, null);

        // 构造 AgentState
        var state = AgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal("帮我总结一下lifepilot的架构设计思想")
                .phase(AgentPhase.UNDERSTANDING)
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
        AssembledContext context = assembler.assemble(state);

        // 断言：检索返回空是正常状态（新系统/首次对话），不应标记为降级
        // 当前代码 BUG：第 163 行 `if (retrievalResults.isEmpty() && state.goal() != null) { degraded = true; }`
        assertFalse(context.degraded(),
                "期望检索返回空时 degraded=false（空记忆是正常状态），但实际 degraded=true"
                        + "（ContextAssembler 将空检索结果误判为系统降级）");
    }
}
