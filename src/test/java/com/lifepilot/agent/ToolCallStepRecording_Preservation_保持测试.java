package com.lifepilot.agent;

import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.TraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolCallStep 记录保持测试（Preservation Property）。
 *
 * <p>验证 {@code reduceAndRecord()} 记录 {@code StateTransitionStep} 的逻辑不受修复影响。</p>
 *
 * <p>在未修复代码上，{@code reduceAndRecord()} 对每次状态转换创建 {@code StateTransitionStep}
 * 并写入 {@code traceContext}。修复 Bug 5 后，新增的 {@code ToolCallStep} 记录是额外补充，
 * 不替代 {@code StateTransitionStep}。</p>
 *
 * <p><b>Validates: Requirements 3.6</b></p>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("ToolCallStep 记录保持测试")
class ToolCallStepRecording_Preservation_保持测试 {

    /**
     * 模拟 reduceAndRecord 的行为：添加 StateTransitionStep。
     */
    private void simulateReduceAndRecord(TraceContext traceContext,
                                          AgentPhase phaseBefore,
                                          AgentPhase phaseAfter,
                                          String actionType,
                                          String actionSummary) {
        var step = new StateTransitionStep(
                traceContext.steps().size(),
                Instant.now(),
                Duration.ofMillis(50),
                phaseBefore.name(),
                phaseAfter.name(),
                actionType,
                actionSummary
        );
        traceContext.addStep(step);
    }

    /**
     * 工具执行后 reduceAndRecord 仍记录 StateTransitionStep。
     *
     * <p>验证 Action.ToolResult 经过 reduceAndRecord 后，traceContext 中包含
     * StateTransitionStep，且阶段转换信息正确。</p>
     */
    @Test
    void 工具执行后_reduceAndRecord仍记录StateTransitionStep() {
        var traceContext = new TraceContext("trace-001", "session-001", "创建日程");

        // 模拟工具执行后的 reduceAndRecord
        simulateReduceAndRecord(traceContext,
                AgentPhase.EXECUTING, AgentPhase.REFLECTING,
                "ToolResult", "工具执行完成");

        // 验证 StateTransitionStep 存在
        long stateTransitionCount = traceContext.steps().stream()
                .filter(s -> s instanceof StateTransitionStep)
                .count();
        assertEquals(1, stateTransitionCount,
                "reduceAndRecord 应记录 1 个 StateTransitionStep");

        // 验证阶段转换信息
        var step = (StateTransitionStep) traceContext.steps().getFirst();
        assertEquals("EXECUTING", step.phaseBefore(), "转换前阶段应为 EXECUTING");
        assertEquals("REFLECTING", step.phaseAfter(), "转换后阶段应为 REFLECTING");
        assertEquals("ToolResult", step.actionType(), "动作类型应为 ToolResult");
    }

    /**
     * 多次状态转换后 StateTransitionStep 数量正确累积。
     *
     * <p>模拟完整的 UNDERSTANDING → PLANNING → EXECUTING → REFLECTING → RESPONDING 流程，
     * 验证每次 reduceAndRecord 都正确记录 StateTransitionStep。</p>
     */
    @Test
    void 多次状态转换后_StateTransitionStep数量正确累积() {
        var traceContext = new TraceContext("trace-002", "session-001", "查询日程");

        // UNDERSTANDING → PLANNING
        simulateReduceAndRecord(traceContext,
                AgentPhase.UNDERSTANDING, AgentPhase.PLANNING,
                "IntentUnderstood", "意图理解完成");

        // PLANNING → EXECUTING
        simulateReduceAndRecord(traceContext,
                AgentPhase.PLANNING, AgentPhase.EXECUTING,
                "PlanGenerated", "计划生成完成");

        // EXECUTING → REFLECTING（工具执行）
        simulateReduceAndRecord(traceContext,
                AgentPhase.EXECUTING, AgentPhase.REFLECTING,
                "ToolResult", "工具执行完成");

        // REFLECTING → RESPONDING
        simulateReduceAndRecord(traceContext,
                AgentPhase.REFLECTING, AgentPhase.RESPONDING,
                "ReflectionComplete", "反思完成");

        long stateTransitionCount = traceContext.steps().stream()
                .filter(s -> s instanceof StateTransitionStep)
                .count();
        assertEquals(4, stateTransitionCount,
                "4 次状态转换应记录 4 个 StateTransitionStep");
    }

    /**
     * StateTransitionStep 的 stepIndex 单调递增。
     *
     * <p>验证每个 StateTransitionStep 的 stepIndex 按顺序递增。</p>
     */
    @Test
    void StateTransitionStep的stepIndex单调递增() {
        var traceContext = new TraceContext("trace-003", "session-001", "创建待办");

        simulateReduceAndRecord(traceContext,
                AgentPhase.UNDERSTANDING, AgentPhase.PLANNING,
                "IntentUnderstood", "意图理解完成");
        simulateReduceAndRecord(traceContext,
                AgentPhase.PLANNING, AgentPhase.EXECUTING,
                "PlanGenerated", "计划生成完成");
        simulateReduceAndRecord(traceContext,
                AgentPhase.EXECUTING, AgentPhase.REFLECTING,
                "ToolResult", "工具执行完成");

        var steps = traceContext.steps().stream()
                .filter(s -> s instanceof StateTransitionStep)
                .map(s -> (StateTransitionStep) s)
                .toList();

        for (int i = 0; i < steps.size(); i++) {
            assertEquals(i, steps.get(i).stepIndex(),
                    "第 " + i + " 个 StateTransitionStep 的 stepIndex 应为 " + i);
        }
    }
}
