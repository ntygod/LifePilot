package com.lifepilot.agent;

import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentPhase;
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
 *   <li>{@code buildDoneEventPayload()} 从 {@code traceContext.steps()} 筛选 {@code ToolCallStep} 时永远为空</li>
 * </ul>
 *
 * <p>修复后（Expected Behavior）：</p>
 * <ul>
 *   <li>{@code coreLoop()} 在 {@code reduceAndRecord()} 之后，对 {@code Action.ToolResult} 补充记录 {@code ToolCallStep}</li>
 *   <li>{@code traceContext.steps()} 包含 {@code ToolCallStep} 实例</li>
 *   <li>{@code buildDoneEventPayload()} 能提取工具调用摘要</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
@DisplayName("ToolCallStep 未写入 TraceContext 探索测试")
class ToolCallStepRecording_BugCondition_探索测试 {

    /**
     * 模拟 reduceAndRecord 的行为：仅添加 StateTransitionStep。
     *
     * <p>这是 AgentLoop.reduceAndRecord() 的核心逻辑 — 它只创建 StateTransitionStep，
     * 不创建 ToolCallStep。本方法复现该行为以验证 bug 存在。</p>
     */
    private void simulateReduceAndRecord(TraceContext traceContext,
                                          AgentPhase phaseBefore,
                                          AgentPhase phaseAfter,
                                          Action action) {
        var step = new StateTransitionStep(
                traceContext.steps().size(),
                Instant.now(),
                Duration.ofMillis(50),
                phaseBefore.name(),
                phaseAfter.name(),
                action.getClass().getSimpleName(),
                "工具执行完成"
        );
        traceContext.addStep(step);
    }

    /**
     * 工具执行后 traceContext 应包含 ToolCallStep。
     *
     * <p>模拟 coreLoop 中工具执行场景：executeNextPlannedToolStep 返回 Action.ToolResult，
     * reduceAndRecord 记录 StateTransitionStep。验证 traceContext.steps() 中是否存在 ToolCallStep。</p>
     *
     * <p><b>Validates: Requirements 1.5, 2.5</b></p>
     */
    @Test
    void 工具执行后_traceContext应包含ToolCallStep() {
        // 创建 TraceContext
        var traceContext = new TraceContext("trace-001", "session-001", "创建日程");

        // 模拟工具执行返回 Action.ToolResult
        var toolResult = new Action.ToolResult(
                "builtin.schedule.create",
                true,
                "{\"id\": \"sch-001\", \"title\": \"开会\"}",
                100,
                250,
                false
        );

        // 模拟 reduceAndRecord — 仅添加 StateTransitionStep（这是当前未修复代码的行为）
        simulateReduceAndRecord(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult);

        // 期望：traceContext.steps() 中应包含 ToolCallStep
        // 未修复代码中，reduceAndRecord 只添加 StateTransitionStep，不添加 ToolCallStep
        boolean hasToolCallStep = traceContext.steps().stream()
                .anyMatch(s -> s instanceof ToolCallStep);

        assertTrue(hasToolCallStep,
                "工具执行后 traceContext.steps() 应包含 ToolCallStep，" +
                "但当前仅有 StateTransitionStep（Bug 5: ToolCallStep 未写入 TraceContext）");
    }

    /**
     * 多次工具执行后 traceContext 应包含对应数量的 ToolCallStep。
     *
     * <p>模拟 ExecutionPlan 包含 2 个步骤的场景，每次工具执行后都应记录 ToolCallStep。</p>
     *
     * <p><b>Validates: Requirements 1.5, 2.5</b></p>
     */
    @Test
    void 多次工具执行后_traceContext应包含对应数量的ToolCallStep() {
        var traceContext = new TraceContext("trace-002", "session-001", "查询并创建日程");

        // 第一次工具执行
        var toolResult1 = new Action.ToolResult(
                "builtin.schedule.query",
                true,
                "[{\"id\": \"sch-001\"}]",
                80,
                150,
                true
        );
        simulateReduceAndRecord(traceContext, AgentPhase.EXECUTING, AgentPhase.EXECUTING, toolResult1);

        // 第二次工具执行
        var toolResult2 = new Action.ToolResult(
                "builtin.schedule.create",
                true,
                "{\"id\": \"sch-002\"}",
                100,
                200,
                false
        );
        simulateReduceAndRecord(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult2);

        // 期望：应有 2 个 ToolCallStep
        long toolCallStepCount = traceContext.steps().stream()
                .filter(s -> s instanceof ToolCallStep)
                .count();

        assertEquals(2, toolCallStepCount,
                "2 次工具执行后应有 2 个 ToolCallStep，" +
                "但当前为 " + toolCallStepCount + "（Bug 5: ToolCallStep 未写入 TraceContext）");
    }

    /**
     * 工具执行失败时也应记录 ToolCallStep。
     *
     * <p>即使工具执行失败（success=false），也应记录 ToolCallStep 以便排查。</p>
     *
     * <p><b>Validates: Requirements 1.5, 2.5</b></p>
     */
    @Test
    void 工具执行失败时_也应记录ToolCallStep() {
        var traceContext = new TraceContext("trace-003", "session-001", "创建日程");

        // 工具执行失败
        var toolResult = new Action.ToolResult(
                "builtin.schedule.create",
                false,
                "工具调用异常: 参数校验失败",
                0,
                50,
                false
        );
        simulateReduceAndRecord(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult);

        boolean hasToolCallStep = traceContext.steps().stream()
                .anyMatch(s -> s instanceof ToolCallStep);

        assertTrue(hasToolCallStep,
                "工具执行失败时也应记录 ToolCallStep，" +
                "但当前仅有 StateTransitionStep（Bug 5: ToolCallStep 未写入 TraceContext）");
    }

    /**
     * buildDoneEventPayload 依赖 ToolCallStep 提取工具摘要。
     *
     * <p>验证 traceContext.steps() 中筛选 ToolCallStep 的逻辑 — 这是 buildDoneEventPayload
     * 提取 toolsSummary 的核心路径。未修复代码中此筛选永远为空。</p>
     *
     * <p><b>Validates: Requirements 2.5</b></p>
     */
    @Test
    void traceContext中ToolCallStep筛选_应返回非空列表() {
        var traceContext = new TraceContext("trace-004", "session-001", "创建日程");

        // 模拟工具执行
        var toolResult = new Action.ToolResult(
                "builtin.schedule.create",
                true,
                "{\"id\": \"sch-001\"}",
                100,
                250,
                false
        );
        simulateReduceAndRecord(traceContext, AgentPhase.EXECUTING, AgentPhase.REFLECTING, toolResult);

        // 模拟 buildDoneEventPayload 中的 ToolCallStep 筛选逻辑
        var toolSummaries = traceContext.steps().stream()
                .filter(s -> s instanceof ToolCallStep)
                .map(s -> (ToolCallStep) s)
                .toList();

        assertFalse(toolSummaries.isEmpty(),
                "buildDoneEventPayload 从 traceContext 筛选 ToolCallStep 应返回非空列表，" +
                "但当前为空（Bug 5: Web UI 工具统计始终显示'暂无工具调用统计'）");
    }
}
