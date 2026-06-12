package com.lifepilot.agent.learning.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceRecord;
import com.lifepilot.observability.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
 * <p>经 {@code TraceRecorder.onTraceEnd} 监听触发；写入失败仅记 WARN，绝不阻塞 Agent 主循环。</p>
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
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 持久化一次执行轨迹。失败仅记 WARN，不抛出。
     *
     * @param record 可观测性产出的完整轨迹快照（内存态，仍保留工具 I/O）
     */
    public void persist(TraceRecord record) {
        if (record == null || record.traceId() == null) {
            return;
        }
        try {
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    """
                    INSERT INTO agent_traces (id, session_id, user_message, final_output, success,
                        error_message, termination_reason, total_steps, total_tokens, duration_ms,
                        model_id, parent_trace_id, depth, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.traceId(),
                    record.sessionId() != null ? record.sessionId() : "",
                    record.goal() != null ? record.goal() : "",
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

            for (TraceStep step : record.steps()) {
                persistStep(record.traceId(), step, now);
            }

            log.debug("学习轨迹: 已落库, traceId={}, steps={}", record.traceId(), record.steps().size());
        } catch (Exception e) {
            log.warn("学习轨迹: 持久化失败, traceId={}, error={}", record.traceId(), e.getMessage());
        }
    }

    private void persistStep(String traceId, TraceStep step, String now) {
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

        jdbcTemplate.update(
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
                step.duration() != null ? step.duration().toMillis() : 0,
                now);
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

    /** action_json 为 NOT NULL 列：写入按步骤类型提炼的小摘要，序列化失败降级为 {}。 */
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
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TOOL_IO_CHARS ? s.substring(0, MAX_TOOL_IO_CHARS) : s;
    }
}
