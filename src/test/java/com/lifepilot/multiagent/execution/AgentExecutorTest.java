package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AgentExecutor 单元测试 — SubAgent 父作用域约束 + Infrastructure 豁免。
 *
 * <p>通过 mock AgentLoop.run() 并捕获 AgentRequest 参数，
 * 验证 buildAllowedToolIds 的父子作用域交集和 infrastructure 豁免逻辑。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
@ExtendWith(MockitoExtension.class)
class AgentExecutorTest {

    @Mock
    private AgentLoop agentLoop;

    @Mock
    private DynamicToolRegistry toolRegistry;

    @Captor
    private ArgumentCaptor<AgentRequest> requestCaptor;

    private AgentExecutor executor;
    private MultiAgentProperties config;

    // infrastructure 工具
    private BuiltinTool infraDatetime;
    private BuiltinTool infraShell;

    // 普通业务工具
    private BuiltinTool toolA;
    private BuiltinTool toolB;
    private BuiltinTool toolC;

    @BeforeEach
    void setUp() {
        config = new MultiAgentProperties();
        config.setMaxDelegationDepth(3);
        executor = new AgentExecutor(agentLoop, toolRegistry, config);

        infraDatetime = buildTool("builtin.env.datetime", "DateTime", RiskLevel.LOW,
                List.of("infrastructure"));
        infraShell = buildTool("builtin.shell.exec", "Shell Exec", RiskLevel.HIGH,
                List.of("infrastructure"));
        toolA = buildTool("tool-a", "Tool A", RiskLevel.LOW, List.of("skill"));
        toolB = buildTool("tool-b", "Tool B", RiskLevel.LOW, List.of("skill"));
        toolC = buildTool("tool-c", "Tool C", RiskLevel.LOW, List.of("skill"));
    }

    // ─────────────────────────────────────────────
    //  父作用域交集约束
    // ─────────────────────────────────────────────

    @Test
    void execute_子Agent工具集与父Agent作用域取交集() {
        // 子 Agent 声明 tool-a, tool-b；父 Agent 作用域 tool-a, tool-c
        // 交集应为 tool-a + infrastructure 工具
        AgentDefinition child = buildDefinition("child", List.of("tool-a", "tool-b"));
        AgentState parent = buildParentState(List.of("tool-a", "tool-c"));

        stubToolResolve("tool-a", toolA);
        stubToolResolve("tool-b", toolB);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .contains("tool-a", "builtin.env.datetime", "builtin.shell.exec")
                .doesNotContain("tool-b");
    }

    @Test
    void execute_父Agent工具集为空_不执行交集_子Agent保留自身工具() {
        // 父 Agent allowedToolIds 为空列表 → 不执行 retainAll
        AgentDefinition child = buildDefinition("child", List.of("tool-a", "tool-b"));
        AgentState parent = buildParentState(List.of());

        stubToolResolve("tool-a", toolA);
        stubToolResolve("tool-b", toolB);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .contains("tool-a", "tool-b", "builtin.env.datetime", "builtin.shell.exec");
    }

    @Test
    void execute_父Agent工具集为null_不执行交集_子Agent保留自身工具() {
        // 父 Agent allowedToolIds 为 null → 不执行 retainAll
        AgentDefinition child = buildDefinition("child", List.of("tool-a", "tool-b"));
        AgentState parent = buildParentState(null);

        stubToolResolve("tool-a", toolA);
        stubToolResolve("tool-b", toolB);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .contains("tool-a", "tool-b", "builtin.env.datetime", "builtin.shell.exec");
    }

    @Test
    void execute_子Agent声明不在父作用域中的工具_被移除() {
        // 子 Agent 声明 tool-a, tool-b, tool-c；父 Agent 作用域仅 tool-a
        // 交集后仅 tool-a + infrastructure
        AgentDefinition child = buildDefinition("child",
                List.of("tool-a", "tool-b", "tool-c"));
        AgentState parent = buildParentState(List.of("tool-a"));

        stubToolResolve("tool-a", toolA);
        stubToolResolve("tool-b", toolB);
        stubToolResolve("tool-c", toolC);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .contains("tool-a", "builtin.env.datetime", "builtin.shell.exec")
                .doesNotContain("tool-b", "tool-c");
    }

    // ─────────────────────────────────────────────
    //  Infrastructure 工具始终保留
    // ─────────────────────────────────────────────

    @Test
    void execute_infrastructure工具始终存在_不受父作用域约束() {
        // 父 Agent 作用域仅 tool-a（不含 infrastructure 工具 ID）
        // 但 infrastructure 工具仍应出现在结果中
        AgentDefinition child = buildDefinition("child", List.of("tool-a"));
        AgentState parent = buildParentState(List.of("tool-a"));

        stubToolResolve("tool-a", toolA);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .contains("builtin.env.datetime", "builtin.shell.exec", "tool-a");
    }

    @Test
    void execute_父子作用域交集为空_仍保留infrastructure工具() {
        // 子 Agent 声明 tool-b；父 Agent 作用域 tool-c → 交集为空
        // 但 infrastructure 工具仍应保留
        AgentDefinition child = buildDefinition("child", List.of("tool-b"));
        AgentState parent = buildParentState(List.of("tool-c"));

        stubToolResolve("tool-b", toolB);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .containsExactlyInAnyOrder("builtin.env.datetime", "builtin.shell.exec");
    }

    // ─────────────────────────────────────────────
    //  自递归排除仍然生效
    // ─────────────────────────────────────────────

    @Test
    void execute_自递归handoff工具仍被排除() {
        String selfHandoffId = HandoffToolFactory.TOOL_ID_PREFIX + "child";
        BuiltinTool selfHandoff = buildTool(selfHandoffId, "Self Handoff",
                RiskLevel.MEDIUM, List.of("handoff"));

        AgentDefinition child = buildDefinition("child",
                List.of("tool-a", selfHandoffId));
        AgentState parent = buildParentState(null);

        stubToolResolve("tool-a", toolA);
        stubToolResolve(selfHandoffId, selfHandoff);
        stubInfraSnapshot();
        stubAgentLoopRun();

        executor.execute(child, "测试任务", null, parent);

        List<String> allowed = captureAllowedToolIds();
        assertThat(allowed)
                .contains("tool-a")
                .doesNotContain(selfHandoffId);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建测试用 BuiltinTool。 */
    private BuiltinTool buildTool(String id, String name, RiskLevel riskLevel,
                                   List<String> tags) {
        return BuiltinTool.builder()
                .id(id)
                .name(name)
                .description("测试工具: " + id)
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .tags(tags)
                .executor(input -> ToolResult.success(Map.of()))
                .build();
    }

    /** 构建子 Agent 定义。 */
    private AgentDefinition buildDefinition(String agentId, List<String> allowedTools) {
        return AgentDefinition.builder()
                .id(agentId)
                .name("Test Agent " + agentId)
                .description("测试 Agent")
                .systemPrompt("你是测试 Agent")
                .allowedTools(allowedTools)
                .canDelegate(false)
                .budget(new AgentBudget(8000, 10, 120))
                .source(new AgentSource.Builtin())
                .build();
    }

    /** 构建父 Agent 状态。 */
    private AgentState buildParentState(List<String> allowedToolIds) {
        return AgentState.builder()
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
                .allowedToolIds(allowedToolIds)
                .build();
    }

    /** Stub toolRegistry.resolve() 返回指定工具。 */
    private void stubToolResolve(String toolId, ToolContract tool) {
        when(toolRegistry.resolve(toolId)).thenReturn(Optional.of(tool));
    }

    /** Stub toolRegistry.getToolSnapshot() 返回 infrastructure 工具列表。 */
    private void stubInfraSnapshot() {
        when(toolRegistry.getToolSnapshot()).thenReturn(
                List.of(infraDatetime, infraShell, toolA, toolB, toolC));
    }

    /** Stub agentLoop.run() 返回成功响应。 */
    private void stubAgentLoopRun() {
        when(agentLoop.run(any())).thenReturn(
                new AgentResponse("sub-trace", "test-session", "完成", 100, 3, null));
    }

    /** 捕获 AgentLoop.run() 接收的 AgentRequest 中的 allowedToolIds。 */
    private List<String> captureAllowedToolIds() {
        org.mockito.Mockito.verify(agentLoop).run(requestCaptor.capture());
        return requestCaptor.getValue().allowedToolIds();
    }
}
