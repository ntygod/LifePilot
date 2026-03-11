package com.lifepilot.tool.bridge;

import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ToolBridgeAgentToolProvider 单元测试。
 *
 * <p>验证 infrastructure 工具白名单豁免逻辑：
 * 无论 allowedToolIds 如何配置，infrastructure 工具始终可见。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
@ExtendWith(MockitoExtension.class)
class ToolBridgeAgentToolProviderTest {

    @Mock
    private DynamicToolRegistry toolRegistry;

    @Mock
    private ToolExecutionPipeline pipeline;

    private ToolBridgeAgentToolProvider provider;

    /** infrastructure 工具（tags 含 "infrastructure"）。 */
    private BuiltinTool infraTool1;
    private BuiltinTool infraTool2;

    /** 普通业务工具（无 infrastructure 标签）。 */
    private BuiltinTool businessTool1;
    private BuiltinTool businessTool2;

    @BeforeEach
    void setUp() {
        provider = new ToolBridgeAgentToolProvider(toolRegistry, pipeline, null);

        infraTool1 = BuiltinTool.builder()
                .id("builtin.env.datetime")
                .name("DateTime")
                .description("获取当前日期时间")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(java.util.Map.of()))
                .build();

        infraTool2 = BuiltinTool.builder()
                .id("builtin.shell.exec")
                .name("Shell Exec")
                .description("执行 Shell 命令")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.HIGH)
                .tags(List.of("infrastructure"))
                .executor(input -> ToolResult.success(java.util.Map.of()))
                .build();

        businessTool1 = BuiltinTool.builder()
                .id("todo.create")
                .name("Create Todo")
                .description("创建待办事项")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(List.of("skill"))
                .executor(input -> ToolResult.success(java.util.Map.of()))
                .build();

        businessTool2 = BuiltinTool.builder()
                .id("schedule.add")
                .name("Add Schedule")
                .description("添加日程")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .tags(List.of("skill"))
                .executor(input -> ToolResult.success(java.util.Map.of()))
                .build();
    }

    // ─────────────────────────────────────────────
    //  allowedToolIds 为空/null — 返回全部工具
    // ─────────────────────────────────────────────

    @Test
    void getToolCallbacks_allowedToolIds为null_返回全部工具() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraTool1, infraTool2, businessTool1, businessTool2));

        AgentState state = buildState(null);
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        assertThat(callbacks).hasSize(4);
        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder(
                        "builtin.env.datetime", "builtin.shell.exec",
                        "todo.create", "schedule.add");
    }

    @Test
    void getToolCallbacks_allowedToolIds为空列表_返回全部工具() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraTool1, infraTool2, businessTool1, businessTool2));

        AgentState state = buildState(List.of());
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        assertThat(callbacks).hasSize(4);
    }

    // ─────────────────────────────────────────────
    //  allowedToolIds 非空 — infrastructure 工具始终可见
    // ─────────────────────────────────────────────

    @Test
    void getToolCallbacks_白名单仅含业务工具_infrastructure工具仍可见() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraTool1, infraTool2, businessTool1, businessTool2));

        // 白名单只包含 todo.create，不包含任何 infrastructure 工具
        AgentState state = buildState(List.of("todo.create"));
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        // 应返回 todo.create + 2 个 infrastructure 工具
        assertThat(callbacks).hasSize(3);
        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder(
                        "builtin.env.datetime", "builtin.shell.exec", "todo.create");
    }

    @Test
    void getToolCallbacks_白名单不含任何已有工具_仅返回infrastructure工具() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraTool1, infraTool2, businessTool1, businessTool2));

        // 白名单包含一个不存在的工具 ID
        AgentState state = buildState(List.of("nonexistent.tool"));
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        // 仅返回 infrastructure 工具
        assertThat(callbacks).hasSize(2);
        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("builtin.env.datetime", "builtin.shell.exec");
    }

    @Test
    void getToolCallbacks_白名单包含infrastructure工具ID_不重复返回() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraTool1, businessTool1));

        // 白名单显式包含 infrastructure 工具 ID
        AgentState state = buildState(List.of("builtin.env.datetime", "todo.create"));
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        // infrastructure 工具不会重复（filter 条件是 OR，同一工具只出现一次）
        assertThat(callbacks).hasSize(2);
        assertThat(callbackNames(callbacks))
                .containsExactlyInAnyOrder("builtin.env.datetime", "todo.create");
    }

    // ─────────────────────────────────────────────
    //  非 infrastructure 工具不在白名单中被过滤
    // ─────────────────────────────────────────────

    @Test
    void getToolCallbacks_非infrastructure工具不在白名单中_被过滤() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraTool1, businessTool1, businessTool2));

        // 白名单只包含 todo.create，schedule.add 不在白名单中
        AgentState state = buildState(List.of("todo.create"));
        List<ToolCallback> callbacks = provider.getToolCallbacks(state);

        assertThat(callbackNames(callbacks))
                .contains("todo.create", "builtin.env.datetime")
                .doesNotContain("schedule.add");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

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

    /** 从 ToolCallback 列表提取工具名称。 */
    private List<String> callbackNames(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .toList();
    }
}
