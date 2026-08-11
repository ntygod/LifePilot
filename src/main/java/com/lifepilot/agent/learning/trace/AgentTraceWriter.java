package com.lifepilot.agent.learning.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.trace.EvaluationStep;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceRecord;
import com.lifepilot.observability.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 学习导向的 Agent 执行轨迹持久化器 — 把一次完整 ReAct 执行的 {@link TraceRecord}
 * 写入 {@code agent_traces} / {@code agent_trace_steps}，作为程序巩固
 * （{@code EpisodicToProceduralConsolidator}）聚类生成操作模板的数据源。
 *
 * <p>与可观测性 {@code traces}/{@code trace_steps} 的区别：可观测性在默认内容策略下
 * 会置空工具 inputJson/outputJson，无法支撑聚类；本写入器保留（脱敏后的）工具 I/O，
 * 并使用面向学习的结构化列（tool_id / tool_input_json 独立列）。</p>
 *
 * <p>经 {@code TraceRecorder.onTraceEnd} 监听触发；写入失败直接抛出，避免程序巩固数据源静默缺失。</p>
 *
 * @author zsg
 * @since 2026-06-06
 */
public class AgentTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceWriter.class);
    private static final int MAX_TOOL_IO_CHARS = 4000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTraceWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 持久化一次执行轨迹。
     *
     * @param record 可观测性产出的完整轨迹快照（内存态，仍保留工具 I/O）
     */
    public void persist(TraceRecord record) {
        validateRecord(record);
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update(
                """
                INSERT INTO agent_traces (id, session_id, user_message, final_output, success,
                    error_message, termination_reason, total_steps, total_tokens, duration_ms,
                    model_id, parent_trace_id, depth, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.traceId(),
                record.sessionId(),
                record.goal(),
                record.finalOutput(),
                record.success() ? 1 : 0,
                record.errorMessage(),
                record.terminationReason(),
                record.totalSteps(),
                record.totalTokens(),
                record.totalDurationMs(),
                firstModelId(record),
                null,
                0,
                now);
        requireSingleRow(rows, "学习轨迹主记录写入", record.traceId());

        for (TraceStep step : record.steps()) {
            persistStep(record.traceId(), step, now);
        }

        log.debug("学习轨迹: 已落库, traceId={}, steps={}", record.traceId(), record.steps().size());
    }

    private void persistStep(String traceId, TraceStep step, String now) {
        validateStep(step);
        String phaseBefore = "";
        String phaseAfter = "";
        String toolId = null;
        String toolInputJson = null;
        String toolOutput = null;
        boolean success = true;
        int blocked = 0;
        String blockReason = null;
        int tokensUsed = 0;

        switch (step) {
            case ToolCallStep tool -> {
                toolId = tool.toolId();
                toolInputJson = truncate(tool.inputJson());
                toolOutput = truncate(tool.outputJson());
                success = tool.success();
            }
            case LlmCallStep llm -> tokensUsed = llm.inputTokens() + llm.outputTokens();
            case GuardrailStep guardrail -> {
                success = guardrail.passed();
                blocked = guardrail.passed() ? 0 : 1;
                blockReason = guardrail.reason();
            }
            case StateTransitionStep state -> {
                phaseBefore = state.phaseBefore();
                phaseAfter = state.phaseAfter();
            }
            default -> { /* EvaluationStep 等：仅记录类型与动作 */ }
        }

        int rows = jdbcTemplate.update(
                """
                INSERT INTO agent_trace_steps (id, trace_id, step_index, phase_before, phase_after,
                    action_type, action_json, tool_id, tool_input_json, tool_output, success,
                    blocked, block_reason, tokens_used, latency_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                traceId,
                step.stepIndex(),
                phaseBefore,
                phaseAfter,
                step.typeName(),
                buildActionJson(step),
                toolId,
                toolInputJson,
                toolOutput,
                success ? 1 : 0,
                blocked,
                blockReason,
                tokensUsed,
                step.duration().toMillis(),
                now);
        requireSingleRow(rows, "学习轨迹步骤写入", traceId + ":" + step.stepIndex());
    }

    /** 取首个 LLM 步的 modelId 作为本次执行的代表模型；无则 null。 */
    private String firstModelId(TraceRecord record) {
        for (TraceStep step : record.steps()) {
            if (step instanceof LlmCallStep llm) {
                return llm.modelId();
            }
        }
        return null;
    }

    /** action_json 为 NOT NULL 列：写入按步骤类型提炼的小摘要。 */
    private String buildActionJson(TraceStep step) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("type", step.typeName());
        switch (step) {
            case ToolCallStep tool -> {
                summary.put("toolId", tool.toolId());
                summary.put("toolAction", tool.toolAction());
            }
            case LlmCallStep llm -> {
                summary.put("scene", llm.scene());
                summary.put("modelId", llm.modelId());
            }
            case StateTransitionStep state -> summary.put("actionType", state.actionType());
            case GuardrailStep guardrail -> summary.put("checkType", guardrail.checkType());
            default -> { /* no extra */ }
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "学习轨迹: action_json 序列化失败, stepIndex=" + step.stepIndex(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TOOL_IO_CHARS ? s.substring(0, MAX_TOOL_IO_CHARS) : s;
    }

    private static void validateRecord(TraceRecord record) {
        Objects.requireNonNull(record, "学习轨迹记录不能为空");
        requireText(record.traceId(), "学习轨迹 traceId");
        requireText(record.sessionId(), "学习轨迹 sessionId");
        requireText(record.goal(), "学习轨迹 goal");
        if (record.steps() == null) {
            throw new IllegalArgumentException("学习轨迹 steps 不能为空");
        }
        if (record.steps().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("学习轨迹 steps 不能包含 null 步骤");
        }
        if (record.totalSteps() != record.steps().size()) {
            throw new IllegalArgumentException("学习轨迹 totalSteps 必须等于 steps 数量: totalSteps="
                    + record.totalSteps() + ", steps=" + record.steps().size());
        }
        record.steps().forEach(AgentTraceWriter::validateStep);
        nonNegative(record.totalDurationMs(), "学习轨迹 duration_ms");
        nonNegative(record.totalTokens(), "学习轨迹 totalTokens");
        nonNegative(record.inputTokens(), "学习轨迹 inputTokens");
        nonNegative(record.outputTokens(), "学习轨迹 outputTokens");
    }

    private static void validateStep(TraceStep step) {
        Objects.requireNonNull(step, "学习轨迹步骤不能为空");
        if (step.stepIndex() < 0) {
            throw new IllegalArgumentException("学习轨迹步骤序号不能为负数: " + step.stepIndex());
        }
        Objects.requireNonNull(step.timestamp(), "学习轨迹步骤时间不能为空");
        requireDuration(step.duration(), "学习轨迹步骤耗时");
        requireText(step.typeName(), "学习轨迹步骤类型");
        switch (step) {
            case ToolCallStep tool -> {
                requireText(tool.toolId(), "工具步骤 toolId");
                requireText(tool.toolAction(), "工具步骤 toolAction");
                Objects.requireNonNull(tool.riskLevel(), "工具步骤风险等级不能为空");
            }
            case LlmCallStep llm -> {
                requireText(llm.providerId(), "LLM 步骤 providerId");
                requireText(llm.modelId(), "LLM 步骤 modelId");
                requireText(llm.scene(), "LLM 步骤 scene");
                nonNegative(llm.inputTokens(), "LLM 步骤 inputTokens");
                nonNegative(llm.outputTokens(), "LLM 步骤 outputTokens");
                requireDuration(llm.latency(), "LLM 步骤 latency");
                if (!Double.isFinite(llm.temperature()) || llm.temperature() < 0.0d) {
                    throw new IllegalArgumentException("LLM 步骤 temperature 必须是非负有限数: "
                            + llm.temperature());
                }
            }
            case GuardrailStep guardrail -> {
                requireText(guardrail.policyId(), "护栏步骤 policyId");
                requireText(guardrail.checkType(), "护栏步骤 checkType");
                Objects.requireNonNull(guardrail.riskLevel(), "护栏步骤风险等级不能为空");
                Objects.requireNonNull(guardrail.approvalMode(), "护栏步骤审批模式不能为空");
                if (!guardrail.passed()) {
                    requireText(guardrail.reason(), "未通过护栏步骤 reason");
                }
            }
            case StateTransitionStep state -> {
                requireText(state.phaseBefore(), "状态步骤 phaseBefore");
                requireText(state.phaseAfter(), "状态步骤 phaseAfter");
                requireText(state.actionType(), "状态步骤 actionType");
            }
            case EvaluationStep evaluation -> {
                probability(evaluation.toolSelectionScore(), "评估步骤 toolSelectionScore");
                probability(evaluation.parameterValidityScore(), "评估步骤 parameterValidityScore");
                probability(evaluation.stepEfficiencyScore(), "评估步骤 stepEfficiencyScore");
                probability(evaluation.policyComplianceScore(), "评估步骤 policyComplianceScore");
                probability(evaluation.tokenEfficiencyScore(), "评估步骤 tokenEfficiencyScore");
                probability(evaluation.overallScore(), "评估步骤 overallScore");
                Objects.requireNonNull(evaluation.violations(), "评估步骤 violations 不能为空");
                Objects.requireNonNull(evaluation.suggestions(), "评估步骤 suggestions 不能为空");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private static void requireDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name + "不能为空");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + "不能为负数: " + duration);
        }
    }

    private static void nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + "不能为负数: " + value);
        }
    }

    private static void probability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(name + "必须在 [0,1] 范围内: " + value);
        }
    }

    private static void requireSingleRow(int rows, String operation, String id) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响行数必须为 1, id=" + id + ", rows=" + rows);
        }
    }
}
