package com.lifepilot.multiagent.execution;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolResultStatus;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SpawnWorkersToolFactory 单元测试。
 *
 * @author zsg
 * @since 2026-03-22
 */
@ExtendWith(MockitoExtension.class)
class SpawnWorkersToolFactoryTest {

    @Mock
    private AgentOrchestrator agentOrchestrator;

    @Mock
    private DynamicToolRegistry toolRegistry;

    private MultiAgentProperties config;
    private SpawnWorkersToolFactory factory;
    private BuiltinTool spawnWorkersTool;

    @BeforeEach
    void setUp() {
        config = new MultiAgentProperties();
        config.setMaxDelegationDepth(2);
        factory = new SpawnWorkersToolFactory(agentOrchestrator, toolRegistry, config);
        spawnWorkersTool = factory.createSpawnWorkersTool();
    }

    @Test
    void 正常派发两个Worker_全部成功() {
        when(toolRegistry.getToolSnapshot()).thenReturn(List.of());
        // 模拟两个 Worker 都成功
        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace-1", "session", "结果A", 100, 3, null))
                .thenReturn(new AgentResponse("trace-2", "session", "结果B", 150, 4, null));

        ToolInput input = buildInput(List.of(
                Map.of("task", "调研A公司"),
                Map.of("task", "调研B公司")
        ));

        ToolResult result = spawnWorkersTool.execute(input);

        assertEquals(ToolResultStatus.SUCCESS, result.status());
        assertNull(result.error());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workers = (List<Map<String, Object>>) result.data().get("workers");
        assertEquals(2, workers.size());
        assertEquals(2L, result.data().get("successCount"));
        assertEquals(0L, result.data().get("failureCount"));

        verify(agentOrchestrator, times(2)).run(any(AgentRequest.class));
    }

    @Test
    void 部分Worker失败_返回PARTIAL_SUCCESS() {
        when(toolRegistry.getToolSnapshot()).thenReturn(List.of());
        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace-1", "session", "结果A", 100, 3, null))
                .thenThrow(new RuntimeException("模拟异常"));

        ToolInput input = buildInput(List.of(
                Map.of("task", "任务A"),
                Map.of("task", "任务B")
        ));

        ToolResult result = spawnWorkersTool.execute(input);

        assertEquals(ToolResultStatus.PARTIAL_SUCCESS, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().contains("部分 Worker 执行失败"));
        assertEquals(1L, result.data().get("successCount"));
        assertEquals(1L, result.data().get("failureCount"));
    }

    @Test
    void 全部Worker失败_返回ERROR() {
        when(toolRegistry.getToolSnapshot()).thenReturn(List.of());
        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("模拟异常"));

        ToolInput input = buildInput(List.of(
                Map.of("task", "任务A"),
                Map.of("task", "任务B")
        ));

        ToolResult result = spawnWorkersTool.execute(input);

        assertEquals(ToolResultStatus.ERROR, result.status());
        assertEquals("所有 Worker 执行失败", result.error());
    }

    @Test
    void 超过最大Worker数量_拒绝执行() {
        config.getParallelWorker().setMaxParallelWorkers(2);

        ToolInput input = buildInput(List.of(
                Map.of("task", "任务A"),
                Map.of("task", "任务B"),
                Map.of("task", "任务C")
        ));

        ToolResult result = spawnWorkersTool.execute(input);

        assertEquals(ToolResultStatus.ERROR, result.status());
        assertTrue(result.error().contains("Worker 数量超限"));
        verify(agentOrchestrator, never()).run(any());
    }

    @Test
    void 委托深度超限_拒绝执行() {
        config.setMaxDelegationDepth(1);

        // _callerDepth = 1，workerDepth = 2 > maxDelegationDepth(1)
        ToolInput input = buildInputWithDepth(
                List.of(Map.of("task", "任务A")),
                1
        );

        ToolResult result = spawnWorkersTool.execute(input);

        assertEquals(ToolResultStatus.ERROR, result.status());
        assertTrue(result.error().contains("委托深度超限"));
        verify(agentOrchestrator, never()).run(any());
    }

    @Test
    void Worker工具白名单排除spawn_workers() {
        // 用真实 BuiltinTool 实例（sealed interface 不能 mock）
        BuiltinTool webSearchTool = BuiltinTool.builder()
                .id("web-search")
                .name("网页搜索")
                .description("搜索")
                .inputSchema(JsonSchema.of(Map.of()))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .tags(List.of())
                .executor(input -> ToolResult.success(Map.of()))
                .build();
        BuiltinTool spawnTool = BuiltinTool.builder()
                .id(SpawnWorkersToolFactory.TOOL_ID)
                .name("spawn")
                .description("spawn")
                .inputSchema(JsonSchema.of(Map.of()))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(List.of())
                .executor(input -> ToolResult.success(Map.of()))
                .build();

        when(toolRegistry.getToolSnapshot()).thenReturn(List.of(webSearchTool, spawnTool));

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace-1", "session", "结果", 100, 3, null));

        ToolInput input = buildInput(List.of(Map.of("task", "测试任务")));
        spawnWorkersTool.execute(input);

        // 验证传给 Worker 的 allowedToolIds 不包含 spawn_workers
        verify(agentOrchestrator).run(argThat(request -> {
            List<String> allowedToolIds = request.allowedToolIds();
            return allowedToolIds != null
                    && allowedToolIds.contains("web-search")
                    && !allowedToolIds.contains(SpawnWorkersToolFactory.TOOL_ID);
        }));
    }

    @Test
    void 空任务列表_返回错误() {
        ToolInput input = buildInput(List.of());

        ToolResult result = spawnWorkersTool.execute(input);

        assertEquals(ToolResultStatus.ERROR, result.status());
        assertTrue(result.error().contains("tasks 不能为空"));
        verify(agentOrchestrator, never()).run(any());
    }

    // ==================== 辅助方法 ====================

    private ToolInput buildInput(List<Map<String, Object>> tasks) {
        return new ToolInput(
                SpawnWorkersToolFactory.TOOL_ID,
                Map.of("tasks", tasks),
                JsonSchema.of(Map.of()),
                null,
                null
        );
    }

    private ToolInput buildInputWithDepth(List<Map<String, Object>> tasks, int depth) {
        return new ToolInput(
                SpawnWorkersToolFactory.TOOL_ID,
                Map.of("tasks", tasks, "_callerDepth", depth),
                JsonSchema.of(Map.of()),
                null,
                null
        );
    }
}
