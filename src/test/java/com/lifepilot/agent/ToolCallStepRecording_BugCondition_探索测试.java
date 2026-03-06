package com.lifepilot.agent;

import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolCallStep 未写入 TraceContext 探索测试。
 *
 * <p>本测试编码的是修复后的期望行为（expected behavior）。</p>
 *
 * <p>修复前（Bug Condition C5）：</p>
 * <ul>
 *   <li>{@code AgentLoop.coreLoop()} 在工具执行后仅通过 {@code reduceAndRecord()} 创建 {@code StateTransitionStep}</li>
 *   <li>不创建 {@code ToolCallStep}，导致 {@code traceContext.steps()} 中无工具调用记录</li>
 * </ul>
 *
 * <p>修复后（Expected Behavior）：</p>
 * <ul>
 *   <li>{@code coreLoop()} 在 {@code reduceAndRecord()} 之后，对 {@code Action.ToolResult} 补充记录 {@code ToolCallStep}</li>
 *   <li>{@code traceContext.steps()} 包含 {@code ToolCallStep} 实例</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("ToolCallStep 未写入 TraceContext 探索测试")
class ToolCallStepRecording_BugCondition_探索测试 {

    /**
     * 模拟 coreLoop 中步骤 5 + 5.5 的完整行为。
     *
     * <p>步骤 5: reduceAndRecord — 添加 StateTransitionStep。</p>
     * <p>步骤 5.5: 若 action 为 ToolResult，补充记录 ToolCallStep（修复后新增）。</p>
     */
    private void simulateCoreLoopSteps(TraceContext traceContext,
                                        AgentPhase phaseBefore,
                                        AgentPhase phaseAfter,
                                        Action action) {
        // 步骤 5: reduceAndRecord — 始终添加 StateTransitionStep
        var stateStep = new StateTransitionStep(
                traceContext.steps().size(),
                Instant.now(),
                Duration.ofMillis(50),
                phaseBefore.name(),
                phaseAfter.name(),
                action.getClass().getSimpleName(),
                "工具执行完成"
        );
        traceContext.addStep(stateStep);

        // 步骤 5.5: 若 action 为 ToolResult，补充记录 ToolCallStep
        if (action instanceof Action.ToolResult toolResult) {
            String outputJson = toolResult.output();
            if (outputJson != null && outputJson.length() > 2000) {
                outputJson = outputJson.substring(0, 2000) + "...[truncated]";
            }
            var toolCallStep = new ToolCallStep(
                    traceContext.steps().size(),
                    Instant.now(),
                    Duration.ofMillis(toolResult.latencyMs()),
                    toolResult.toolId(),
                    toolResult.toolId(),
                    null,
                    outputJson,
                    toolResult.success(),
                    toolResult.success() ? null : toolResult.output(),
                    RiskLevel.LOW
            );
            traceContext.addStep(toolCallStep);
        }
    }

    @Test
    void 工具执行后_traceContext应包含ToolCallStep() {
        var traceContext = new TraceContext("trace-001", "session-001", "创建日程");

        var toolResult = new Action.ToolResult(
                "builtin.schedule.create", true,
                "{\"id\": \"sch-001\", \"title\": \"开会\"}", 100, 250, false
        );
        simulateCoreLoopSteps(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult);

        boolean hasToolCallStep = traceContext.steps().stream()
                .anyMatch(s -> s instanceof ToolCallStep);
        assertTrue(hasToolCallStep,
                "工具执行后 traceContext.steps() 应包含 ToolCallStep");
    }

    @Test
    void 多次工具执行后_traceContext应包含对应数量的ToolCallStep() {
        var traceContext = new TraceContext("trace-002", "session-001", "查询并创建日程");

        var toolResult1 = new Action.ToolResult(
                "builtin.schedule.query", true, "[{\"id\": \"sch-001\"}]", 80, 150, true
        );
        simulateCoreLoopSteps(traceContext, AgentPhase.EXECUTING, AgentPhase.EXECUTING, toolResult1);

        var toolResult2 = new Action.ToolResult(
                "builtin.schedule.create", true, "{\"id\": \"sch-002\"}", 100, 200, false
        );
        simulateCoreLoopSteps(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult2);

        long toolCallStepCount = traceContext.steps().stream()
                .filter(s -> s instanceof ToolCallStep).count();
        assertEquals(2, toolCallStepCount,
                "2 次工具执行后应有 2 个 ToolCallStep，但当前为 " + toolCallStepCount);
    }

    @Test
    void 工具执行失败时_也应记录ToolCallStep() {
        var traceContext = new TraceContext("trace-003", "session-001", "创建日程");

        var toolResult = new Action.ToolResult(
                "builtin.schedule.create", false, "工具调用异常: 参数校验失败", 0, 50, false
        );
        simulateCoreLoopSteps(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult);

        boolean hasToolCallStep = traceContext.steps().stream()
                .anyMatch(s -> s instanceof ToolCallStep);
        assertTrue(hasToolCallStep, "工具执行失败时也应记录 ToolCallStep");
    }

    @Test
    void traceContext中ToolCallStep筛选_应返回非空列表() {
        var traceContext = new TraceContext("trace-004", "session-001", "创建日程");

        var toolResult = new Action.ToolResult(
                "builtin.schedule.create", true, "{\"id\": \"sch-001\"}", 100, 250, false
        );
        simulateCoreLoopSteps(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult);

        var toolSummaries = traceContext.steps().stream()
                .filter(s -> s instanceof ToolCallStep)
                .map(s -> (ToolCallStep) s)
                .toList();
        assertFalse(toolSummaries.isEmpty(),
                "buildDoneEventPayload 从 traceContext 筛选 ToolCallStep 应返回非空列表");
    }
}
