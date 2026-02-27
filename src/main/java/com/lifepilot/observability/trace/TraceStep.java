package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.RiskLevel;
import jakarta.annotation.Nullable;

/**
 * 追踪步骤 sealed interface — 定义 Agent 执行过程中的五种步骤类型。
 *
 * <p>通过 sealed interface + record 实现编译时穷举检查，
 * 确保每种步骤类型都被正确处理。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public sealed interface TraceStep
        permits LlmCallStep, ToolCallStep, GuardrailStep,
                StateTransitionStep, EvaluationStep {

    /**
     * 步骤在 Trace 中的序号（从 0 开始）。
     */
    int stepIndex();

    /**
     * 步骤发生的时间戳。
     */
    Instant timestamp();

    /**
     * 步骤耗时。
     */
    Duration duration();

    /**
     * 步骤类型名称，用于序列化/反序列化时的类型标识。
     */
    String typeName();
}

/**
 * LLM 调用步骤 — 记录一次 LLM 调用的完整信息，对齐 OpenTelemetry GenAI 语义约定。
 *
 * @param stepIndex    步骤序号
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param providerId   LLM 提供商 ID
 * @param modelId      模型 ID
 * @param scene        调用场景
 * @param inputTokens  输入 Token 数
 * @param outputTokens 输出 Token 数
 * @param latency      LLM 响应延迟
 * @param cacheHit     是否命中缓存
 * @param temperature  采样温度
 * @param finishReason 完成原因（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
record LlmCallStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String providerId,
        String modelId,
        String scene,
        int inputTokens,
        int outputTokens,
        Duration latency,
        boolean cacheHit,
        double temperature,
        @Nullable String finishReason
) implements TraceStep {

    @Override
    public String typeName() {
        return "llm_call";
    }
}


/**
 * 工具调用步骤 — 记录一次工具调用的完整信息。
 *
 * @param stepIndex    步骤序号
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param toolId       工具 ID
 * @param toolAction   工具动作
 * @param inputJson    输入 JSON（可为 null）
 * @param outputJson   输出 JSON（可为 null）
 * @param success      是否成功
 * @param errorMessage 错误信息（可为 null）
 * @param riskLevel    风险等级
 * @author zsg
 * @since 2026-02-27
 */
record ToolCallStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String toolId,
        String toolAction,
        @Nullable String inputJson,
        @Nullable String outputJson,
        boolean success,
        @Nullable String errorMessage,
        RiskLevel riskLevel
) implements TraceStep {

    @Override
    public String typeName() {
        return "tool_call";
    }
}

/**
 * 护栏检查步骤 — 记录一次护栏策略检查的结果。
 *
 * @param stepIndex    步骤序号
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param policyId     策略 ID
 * @param checkType    检查类型
 * @param passed       是否通过
 * @param reason       原因（可为 null）
 * @param riskLevel    风险等级
 * @param approvalMode 审批模式
 * @author zsg
 * @since 2026-02-27
 */
record GuardrailStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String policyId,
        String checkType,
        boolean passed,
        @Nullable String reason,
        RiskLevel riskLevel,
        ApprovalMode approvalMode
) implements TraceStep {

    @Override
    public String typeName() {
        return "guardrail";
    }
}

/**
 * 状态转换步骤 — 记录 Agent 状态机的一次状态转换。
 *
 * @param stepIndex     步骤序号
 * @param timestamp     发生时间
 * @param duration      耗时
 * @param phaseBefore   转换前阶段
 * @param phaseAfter    转换后阶段
 * @param actionType    触发转换的动作类型
 * @param actionSummary 动作摘要
 * @author zsg
 * @since 2026-02-27
 */
record StateTransitionStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String phaseBefore,
        String phaseAfter,
        String actionType,
        String actionSummary
) implements TraceStep {

    @Override
    public String typeName() {
        return "state_transition";
    }
}

/**
 * 评估步骤 — 记录轨迹评估的五维评分结果。
 *
 * @param stepIndex              步骤序号
 * @param timestamp              发生时间
 * @param duration               耗时
 * @param toolSelectionScore     工具选择正确性评分
 * @param parameterValidityScore 参数合法性评分
 * @param stepEfficiencyScore    步骤效率评分
 * @param policyComplianceScore  策略合规性评分
 * @param tokenEfficiencyScore   Token 效率评分
 * @param overallScore           综合评分
 * @param violations             违规项列表
 * @param suggestions            建议列表
 * @author zsg
 * @since 2026-02-27
 */
record EvaluationStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        double toolSelectionScore,
        double parameterValidityScore,
        double stepEfficiencyScore,
        double policyComplianceScore,
        double tokenEfficiencyScore,
        double overallScore,
        List<String> violations,
        List<String> suggestions
) implements TraceStep {

    /**
     * 紧凑构造函数 — 使用 List.copyOf() 保证集合字段不可变性。
     */
    EvaluationStep {
        violations = List.copyOf(violations);
        suggestions = List.copyOf(suggestions);
    }

    @Override
    public String typeName() {
        return "evaluation";
    }
}
