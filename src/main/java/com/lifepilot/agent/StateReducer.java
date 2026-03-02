package com.lifepilot.agent;

import com.lifepilot.agent.model.*;
import com.lifepilot.observability.guardrail.RiskLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯函数状态转换器。
 *
 * <p>核心契约：reduce(state, action) → newState。
 * 相同输入永远产生相同输出，无副作用。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class StateReducer {

    /** 反思→规划回退最大次数。 */
    private static final int MAX_REVISION_CYCLES = 2;

    /**
     * 核心状态转换方法。
     *
     * @param state  当前不可变状态
     * @param action 待处理的动作
     * @return 新的不可变状态
     * @throws IllegalStateException 阶段转换不合法时
     */
    public AgentState reduce(AgentState state, Action action) {
        // 吸收态：TERMINATED 后忽略所有动作
        if (state.phase().isTerminal()) {
            return state;
        }

        return switch (action) {
            case Action.IntentUnderstood a    -> reduceIntentUnderstood(state, a);
            case Action.PlanGenerated a       -> reducePlanGenerated(state, a);
            case Action.ToolResult a          -> reduceToolResult(state, a);
            case Action.ReflectionComplete a  -> reduceReflectionComplete(state, a);
            case Action.ResponseGenerated a   -> reduceResponseGenerated(state, a);
            case Action.BudgetExhausted a     -> reduceBudgetExhausted(state, a);
            case Action.Blocked a             -> reduceBlocked(state, a);
            case Action.ErrorRecovery a       -> reduceErrorRecovery(state, a);
            case Action.SubAgentResult a      -> reduceSubAgentResult(state, a);
        };
    }

    private AgentState reduceIntentUnderstood(AgentState state, Action.IntentUnderstood a) {
        if (a.needsClarification()) {
            validateTransition(state.phase(), AgentPhase.RESPONDING);
            return state.toBuilder()
                    .phase(AgentPhase.RESPONDING)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), null, true, a.summary(), false, 0, 0))
                    .finalOutput(a.clarificationQuestion())
                    .done(true)
                    .terminationReason("需要用户澄清")
                    .build();
        }
        
        // SIMPLE 复杂度直接进入 RESPONDING，跳过 PLANNING/EXECUTING
        if (a.complexity() == TaskComplexity.SIMPLE) {
            validateTransition(state.phase(), AgentPhase.RESPONDING);
            var entities = new ArrayList<>(state.mentionedEntities());
            entities.addAll(a.entities());
            return state.toBuilder()
                    .phase(AgentPhase.RESPONDING)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), null, true, a.summary(), false, 0, 0))
                    .mentionedEntities(entities)
                    .build();
        }
        
        // MODERATE / COMPLEX → PLANNING
        validateTransition(state.phase(), AgentPhase.PLANNING);
        var entities = new ArrayList<>(state.mentionedEntities());
        entities.addAll(a.entities());
        return state.toBuilder()
                .phase(AgentPhase.PLANNING)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), null, true, a.summary(), false, 0, 0))
                .mentionedEntities(entities)
                .build();
    }

    private AgentState reducePlanGenerated(AgentState state, Action.PlanGenerated a) {
        validateTransition(state.phase(), AgentPhase.EXECUTING);
        var plan = new ExecutionPlan(a.steps(), a.estimatedTokens(), a.rationale());
        return state.toBuilder()
                .phase(AgentPhase.EXECUTING)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), null, true, a.rationale(), false, 0, 0))
                .plan(plan)
                .planStepIndex(0)
                .build();
    }

    private AgentState reduceToolResult(AgentState state, Action.ToolResult a) {
        AgentPhase targetPhase = a.hasMore() ? AgentPhase.EXECUTING : AgentPhase.REFLECTING;
        validateTransition(state.phase(), targetPhase);
        return state.toBuilder()
                .phase(targetPhase)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), a.toolId(), a.success(), a.output(), false, a.tokensUsed(), a.latencyMs()))
                .planStepIndex(state.planStepIndex() + 1)
                .budget(state.budget().deductTokens(a.tokensUsed()))
                .build();
    }

    private AgentState reduceReflectionComplete(AgentState state, Action.ReflectionComplete a) {
        if (a.satisfied()) {
            validateTransition(state.phase(), AgentPhase.RESPONDING);
            return state.toBuilder()
                    .phase(AgentPhase.RESPONDING)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), null, true, a.summary(), false, 0, 0))
                    .build();
        }
        if (a.needsReplanning()) {
            if (state.revisionCount() >= MAX_REVISION_CYCLES) {
                // 超过最大修正次数，强制进入响应
                validateTransition(state.phase(), AgentPhase.RESPONDING);
                return state.toBuilder()
                        .phase(AgentPhase.RESPONDING)
                        .stepCount(state.stepCount() + 1)
                        .steps(appendStep(state.steps(), null, true, "修正循环达到上限，强制进入响应", false, 0, 0))
                        .build();
            }
            validateTransition(state.phase(), AgentPhase.PLANNING);
            return state.toBuilder()
                    .phase(AgentPhase.PLANNING)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), null, true, a.summary(), false, 0, 0))
                    .revisionCount(state.revisionCount() + 1)
                    .build();
        }
        // 不满意但不需要重新规划 → 调整后重新执行
        validateTransition(state.phase(), AgentPhase.EXECUTING);
        return state.toBuilder()
                .phase(AgentPhase.EXECUTING)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), null, true, a.summary(), false, 0, 0))
                .build();
    }

    private AgentState reduceResponseGenerated(AgentState state, Action.ResponseGenerated a) {
        validateTransition(state.phase(), AgentPhase.TERMINATED);
        return state.toBuilder()
                .phase(AgentPhase.TERMINATED)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), null, true, a.content(), false, 0, 0))
                .done(true)
                .finalOutput(a.content())
                .terminationReason(null)
                .build();
    }

    private AgentState reduceBudgetExhausted(AgentState state, Action.BudgetExhausted a) {
        validateTransition(state.phase(), AgentPhase.TERMINATED);
        return state.toBuilder()
                .phase(AgentPhase.TERMINATED)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), null, false, a.reason(), false, 0, 0))
                .done(true)
                .terminationReason(a.reason())
                .build();
    }

    private AgentState reduceBlocked(AgentState state, Action.Blocked a) {
        if (a.riskLevel() == RiskLevel.CRITICAL) {
            validateTransition(state.phase(), AgentPhase.TERMINATED);
            return state.toBuilder()
                    .phase(AgentPhase.TERMINATED)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), a.toolId(), false, a.reason(), true, 0, 0))
                    .done(true)
                    .terminationReason("护栏阻断: " + a.reason())
                    .build();
        }
        if (a.riskLevel() == RiskLevel.HIGH) {
            validateTransition(state.phase(), AgentPhase.UNDERSTANDING);
            return state.toBuilder()
                    .phase(AgentPhase.UNDERSTANDING)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), a.toolId(), false, a.reason(), true, 0, 0))
                    .build();
        }
        // LOW / MEDIUM / null → 保持当前阶段
        return state.toBuilder()
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), a.toolId(), false, a.reason(), true, 0, 0))
                .build();
    }

    private AgentState reduceErrorRecovery(AgentState state, Action.ErrorRecovery a) {
        if (!a.recoverable()) {
            validateTransition(state.phase(), AgentPhase.TERMINATED);
            return state.toBuilder()
                    .phase(AgentPhase.TERMINATED)
                    .stepCount(state.stepCount() + 1)
                    .steps(appendStep(state.steps(), null, false, a.errorMessage(), false, 0, 0))
                    .done(true)
                    .terminationReason("不可恢复错误: " + a.errorType())
                    .build();
        }
        // 可恢复 → 保持当前阶段，允许重试
        return state.toBuilder()
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), null, false, a.errorMessage(), false, 0, 0))
                .build();
    }

    private AgentState reduceSubAgentResult(AgentState state, Action.SubAgentResult a) {
        validateTransition(state.phase(), AgentPhase.REFLECTING);
        return state.toBuilder()
                .phase(AgentPhase.REFLECTING)
                .stepCount(state.stepCount() + 1)
                .steps(appendStep(state.steps(), a.skillId(), a.success(), a.output(), false, a.tokensUsed(), 0))
                .budget(state.budget().deductTokens(a.tokensUsed()))
                .build();
    }

    /** 验证阶段转换合法性。 */
    private void validateTransition(AgentPhase from, AgentPhase to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("非法阶段转换: " + from + " → " + to);
        }
    }

    /** 追加 StepRecord 到 steps 列表。 */
    private List<StepRecord> appendStep(List<StepRecord> steps, String toolId,
                                         boolean success, String output,
                                         boolean blocked, int tokensUsed, long latencyMs) {
        var newSteps = new ArrayList<>(steps);
        newSteps.add(new StepRecord(toolId, success, output, blocked, tokensUsed, latencyMs));
        return newSteps;
    }

}
