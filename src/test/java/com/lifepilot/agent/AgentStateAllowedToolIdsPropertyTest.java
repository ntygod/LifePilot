package com.lifepilot.agent;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.bridge.ToolBridgeAgentToolProvider;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import net.jqwik.api.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentState 白名单传播完整性属性测试。
 *
 * <p>使用 jqwik 生成随机 allowedToolIds 列表，验证：
 * <ul>
 *   <li>AgentState.init() 后 state.allowedToolIds() 等于 request.allowedToolIds()</li>
 *   <li>ToolBridgeAgentToolProvider.getToolCallbacks(state) 返回的工具是白名单子集</li>
 * </ul></p>
 *
 * <p><b>Validates: Property 1, Requirements 2.2</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class AgentStateAllowedToolIdsPropertyTest {

    /** 工具 ID 候选池 — 模拟真实场景中的工具 ID 格式。 */
    private static final List<String> TOOL_ID_POOL = List.of(
            "builtin.todo.create", "builtin.todo.list", "builtin.todo.delete",
            "builtin.schedule.create", "builtin.schedule.list",
            "builtin.habit.create", "builtin.habit.track",
            "builtin.memory.search", "builtin.memory.save",
            "handoff_to_writer", "handoff_to_analyst", "handoff_to_researcher",
            "mcp.weather.query", "mcp.search.web", "yaml.custom.tool"
    );

    // ─────────────────────────────────────────────
    //  属性 1.1 — init() 后白名单等于 request 的白名单
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.2</b>
     *
     * <p>对任意非空 allowedToolIds 列表，AgentState.init(request) 后
     * state.allowedToolIds() 应与 request.allowedToolIds() 内容相等。</p>
     */
    @Property(tries = 200)
    void allowedToolIds_init后等于request的allowedToolIds(
            @ForAll("allowedToolIdLists") List<String> allowedToolIds) {

        var request = new AgentRequest(
                "测试消息", "session-1", "cli",
                null, null, null, 0, null,
                allowedToolIds, null);

        AgentState state = AgentState.init(request);

        assertNotNull(state.allowedToolIds(),
                "init() 后 allowedToolIds 不应为 null（request 携带了非空白名单）");
        assertEquals(allowedToolIds, state.allowedToolIds(),
                "init() 后 state.allowedToolIds() 应等于 request.allowedToolIds()");
    }

    /**
     * <b>Validates: Requirements 2.2</b>
     *
     * <p>当 request.allowedToolIds() 为 null 时，
     * AgentState.init(request) 后 state.allowedToolIds() 也应为 null。</p>
     */
    @Property(tries = 50)
    void allowedToolIds_null时init后也为null(
            @ForAll("randomMessages") String message) {

        var request = new AgentRequest(
                message, "session-1", "cli",
                null, null, null, 0, null,
                null, null);

        AgentState state = AgentState.init(request);

        assertNull(state.allowedToolIds(),
                "request.allowedToolIds() 为 null 时，init() 后也应为 null");
    }

    // ─────────────────────────────────────────────
    //  属性 1.2 — getToolCallbacks 返回的工具是白名单子集
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.2</b>
     *
     * <p>对任意 allowedToolIds 子集，ToolBridgeAgentToolProvider.getToolCallbacks(state)
     * 返回的工具 ID 集合应是 allowedToolIds 的子集。</p>
     */
    @Property(tries = 200)
    void getToolCallbacks_返回工具是白名单子集(
            @ForAll("allowedToolIdSubsets") AllowedToolIdSubset subset) {

        // 构建 registry 并注册所有候选工具
        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);

        for (String toolId : subset.registeredToolIds()) {
            toolRegistry.registerBuiltinTool(buildDummyTool(toolId));
        }

        // 构造 state，白名单为 subset.allowedToolIds()
        var request = new AgentRequest(
                "测试消息", "session-1", "cli",
                null, null, null, 0, null,
                subset.allowedToolIds(), null);
        AgentState state = AgentState.init(request);

        // 构造 provider
        var pipeline = mock(ToolExecutionPipeline.class);
        var provider = new ToolBridgeAgentToolProvider(toolRegistry, pipeline, null);

        // 执行
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        // 收集返回的工具名称
        Set<String> returnedToolIds = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());

        // 断言：返回的工具 ID 集合是白名单的子集
        Set<String> allowedSet = new HashSet<>(subset.allowedToolIds());
        assertTrue(allowedSet.containsAll(returnedToolIds),
                "返回的工具 " + returnedToolIds + " 应是白名单 " + allowedSet + " 的子集");

        // 断言：返回的工具数量不超过白名单大小
        assertTrue(callbacks.size() <= subset.allowedToolIds().size(),
                "返回工具数量 " + callbacks.size() + " 不应超过白名单大小 " + subset.allowedToolIds().size());
    }

    /**
     * <b>Validates: Requirements 2.2</b>
     *
     * <p>当 allowedToolIds 为 null 时，getToolCallbacks 应返回全量工具。</p>
     */
    @Property(tries = 50)
    void getToolCallbacks_白名单为null时返回全量工具(
            @ForAll("registeredToolIdSets") List<String> registeredToolIds) {

        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var toolRegistry = new DynamicToolRegistry(guardrailEngine, eventPublisher);

        for (String toolId : registeredToolIds) {
            toolRegistry.registerBuiltinTool(buildDummyTool(toolId));
        }

        // allowedToolIds = null → 不限制
        var request = new AgentRequest(
                "测试消息", "session-1", "cli",
                null, null, null, 0, null,
                null, null);
        AgentState state = AgentState.init(request);

        var pipeline = mock(ToolExecutionPipeline.class);
        var provider = new ToolBridgeAgentToolProvider(toolRegistry, pipeline, null);

        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        assertEquals(registeredToolIds.size(), callbacks.size(),
                "白名单为 null 时应返回全量工具（" + registeredToolIds.size() + " 个）");
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成非空 allowedToolIds 列表（从候选池中随机选取 1~8 个不重复 ID）。 */
    @Provide
    Arbitrary<List<String>> allowedToolIdLists() {
        return Arbitraries.of(TOOL_ID_POOL)
                .list().ofMinSize(1).ofMaxSize(8)
                .uniqueElements()
                .map(List::copyOf);
    }

    /** 生成随机消息字符串。 */
    @Provide
    Arbitrary<String> randomMessages() {
        return Arbitraries.of(
                "帮我创建一个待办",
                "总结一下架构设计",
                "查询天气",
                "写一篇文章",
                "分析数据"
        );
    }

    /** 生成注册工具 ID 集合（从候选池中随机选取 1~10 个不重复 ID）。 */
    @Provide
    Arbitrary<List<String>> registeredToolIdSets() {
        return Arbitraries.of(TOOL_ID_POOL)
                .list().ofMinSize(1).ofMaxSize(10)
                .uniqueElements()
                .map(List::copyOf);
    }

    /**
     * 生成 AllowedToolIdSubset — 注册工具集合 + 白名单子集。
     *
     * <p>白名单可能包含注册表中不存在的工具 ID（模拟配置错误场景），
     * 也可能是注册表的严格子集（正常场景）。</p>
     */
    @Provide
    Arbitrary<AllowedToolIdSubset> allowedToolIdSubsets() {
        return Arbitraries.of(TOOL_ID_POOL)
                .list().ofMinSize(2).ofMaxSize(10)
                .uniqueElements()
                .flatMap(registered -> {
                    // 白名单从注册工具中随机选取子集
                    return Arbitraries.of(registered)
                            .list().ofMinSize(1).ofMaxSize(registered.size())
                            .uniqueElements()
                            .map(allowed -> new AllowedToolIdSubset(
                                    List.copyOf(registered),
                                    List.copyOf(allowed)));
                });
    }

    /** 注册工具集合 + 白名单子集的组合数据。 */
    record AllowedToolIdSubset(List<String> registeredToolIds, List<String> allowedToolIds) {}

    /** 构建 dummy BuiltinTool（仅用于注册到 registry）。 */
    private static BuiltinTool buildDummyTool(String toolId) {
        List<String> tags = toolId.startsWith("handoff_to_")
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
