package com.lifepilot.agent.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Agent 动作密封接口。
 *
 * <p>定义 LLM 产生和系统产生的所有动作类型，
 * 是概率域与确定性域之间的桥梁。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public sealed interface Action permits
        Action.IntentUnderstood,
        Action.PlanGenerated,
        Action.ToolResult,
        Action.ReflectionComplete,
        Action.ResponseGenerated,
        Action.BudgetExhausted,
        Action.Blocked,
        Action.ErrorRecovery,
        Action.SubAgentResult {

    /** 意图理解结果。 */
    record IntentUnderstood(
            String summary,
            boolean needsClarification,
            @Nullable String clarificationQuestion,
            boolean canProceed,
            List<String> entities,
            TaskComplexity complexity
    ) implements Action {
        /** 紧凑构造器 — 防御性拷贝。 */
        public IntentUnderstood {
            entities = List.copyOf(entities);
        }
    }

    /** 计划生成结果。 */
    record PlanGenerated(
            List<PlanStep> steps,
            int estimatedTokens,
            String rationale
    ) implements Action {
        /** 紧凑构造器 — 防御性拷贝。 */
        public PlanGenerated {
            steps = List.copyOf(steps);
        }
    }

    /** 工具执行结果。 */
    record ToolResult(
            String toolId,
            boolean success,
            String output,
            int tokensUsed,
            long latencyMs,
            boolean hasMore
    ) implements Action {
    }

    /** 反思评估结果。 */
    record ReflectionComplete(
            boolean satisfied,
            @Nullable String adjustmentPlan,
            String summary,
            boolean needsReplanning
    ) implements Action {
    }

    /** 响应生成结果。 */
    record ResponseGenerated(
            String content,
            List<String> suggestions
    ) implements Action {
        /** 紧凑构造器 — 防御性拷贝。 */
        public ResponseGenerated {
            suggestions = List.copyOf(suggestions);
        }
    }

    /** 预算耗尽。 */
    record BudgetExhausted(String reason) implements Action {
    }

    /** 护栏阻断。 */
    record Blocked(
            String reason,
            @Nullable RiskLevel riskLevel,
            @Nullable String toolId
    ) implements Action {
        /** 简化构造器，仅接受 reason。 */
        public Blocked(String reason) {
            this(reason, null, null);
        }
    }

    /** 错误恢复。 */
    record ErrorRecovery(
            AgentErrorType errorType,
            String errorMessage,
            boolean recoverable,
            @Nullable String recoveryStrategy
    ) implements Action {
    }

    /** 子 Agent 执行结果。 */
    record SubAgentResult(
            String subTraceId,
            String skillId,
            boolean success,
            String output,
            int tokensUsed
    ) implements Action {
    }
}
