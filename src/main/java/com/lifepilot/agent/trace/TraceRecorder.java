package com.lifepilot.agent.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 决策轨迹记录器。
 *
 * <p>记录每步的输入/输出/状态变化/Token 消耗，支持完整回放。
 * 内存中使用 ConcurrentHashMap 缓存轨迹，persistTrace() 时写入 SQLite。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, TraceContext> traceCache = new ConcurrentHashMap<>();

    public TraceRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建新的轨迹记录上下文。
     *
     * @param traceId     轨迹 ID
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 轨迹上下文
     */
    public TraceContext startTrace(String traceId, String sessionId, String userMessage) {
        var context = new TraceContext(traceId, sessionId, userMessage, new ArrayList<>(), Instant.now());
        traceCache.put(traceId, context);
        log.debug("轨迹记录开始: traceId={}, sessionId={}", traceId, sessionId);
        return context;
    }

    /**
     * 记录单步轨迹信息。
     *
     * @param context 轨迹上下文
     * @param step    单步轨迹
     */
    public void recordStep(TraceContext context, TraceStep step) {
        context.steps().add(step);
        log.debug("轨迹步骤记录: traceId={}, stepIndex={}, phase={} → {}",
                context.traceId(), step.stepIndex(), step.phaseBefore(), step.phaseAfter());
    }

    /**
     * 完成轨迹记录。
     *
     * @param context           轨迹上下文
     * @param finalOutput       最终输出
     * @param success           是否成功
     * @param errorMessage      错误消息
     * @param terminationReason 终止原因
     */
    public void endTrace(TraceContext context, @Nullable String finalOutput,
                         boolean success, @Nullable String errorMessage,
                         @Nullable String terminationReason) {
        context.setFinalOutput(finalOutput);
        context.setSuccess(success);
        context.setErrorMessage(errorMessage);
        context.setTerminationReason(terminationReason);
        context.setEndTime(Instant.now());
        log.debug("轨迹记录完成: traceId={}, success={}, steps={}",
                context.traceId(), success, context.steps().size());
    }

    /**
     * 将内存中的轨迹数据持久化到 SQLite。
     *
     * <p>持久化失败时记录 WARN 日志，不抛出异常。</p>
     *
     * @param traceId 轨迹 ID
     */
    public void persistTrace(String traceId) {
        var context = traceCache.remove(traceId);
        if (context == null) {
            log.warn("轨迹持久化跳过: traceId={} 不存在于缓存", traceId);
            return;
        }

        try {
            long durationMs = context.endTime() != null
                    ? Duration.between(context.startTime(), context.endTime()).toMillis()
                    : 0;
            int totalTokens = context.steps().stream().mapToInt(TraceStep::tokensUsed).sum();

            // 写入 agent_traces 表
            jdbcTemplate.update(
                    """
                    INSERT INTO agent_traces (id, session_id, user_message, final_output,
                        success, error_message, termination_reason, total_steps, total_tokens,
                        duration_ms, model_id, parent_trace_id, depth, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    context.traceId(), context.sessionId(), context.userMessage(),
                    context.finalOutput(), context.success() ? 1 : 0,
                    context.errorMessage(), context.terminationReason(),
                    context.steps().size(), totalTokens, durationMs,
                    null, null, 0, Instant.now().toString()
            );

            // 写入 agent_trace_steps 表
            for (var step : context.steps()) {
                String actionJson = serializeAction(step);
                jdbcTemplate.update(
                        """
                        INSERT INTO agent_trace_steps (id, trace_id, step_index, phase_before,
                            phase_after, action_type, action_json, tool_id, tool_input_json,
                            tool_output, success, blocked, block_reason, tokens_used,
                            latency_ms, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID().toString(), step.traceId(), step.stepIndex(),
                        step.phaseBefore().name(), step.phaseAfter().name(),
                        step.action().getClass().getSimpleName(), actionJson,
                        step.toolId(), step.toolInput(), step.toolOutput(),
                        step.blocked() ? 0 : 1, step.blocked() ? 1 : 0,
                        step.blockReason(), step.tokensUsed(), step.latencyMs(),
                        step.timestamp().toString()
                );
            }

            log.info("轨迹持久化成功: traceId={}, steps={}", traceId, context.steps().size());
        } catch (Exception e) {
            log.warn("轨迹持久化失败: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 从数据库加载完整轨迹记录。
     *
     * @param traceId 轨迹 ID
     * @return 轨迹记录
     */
    public Optional<TraceRecord> findTrace(String traceId) {
        try {
            var traces = jdbcTemplate.query(
                    "SELECT * FROM agent_traces WHERE id = ?",
                    (rs, rowNum) -> new TraceRecord(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("user_message"),
                            rs.getString("final_output"),
                            rs.getInt("success") == 1,
                            rs.getString("error_message"),
                            rs.getString("termination_reason"),
                            rs.getInt("total_steps"),
                            rs.getInt("total_tokens"),
                            rs.getLong("duration_ms"),
                            rs.getString("created_at")
                    ),
                    traceId
            );
            return traces.isEmpty() ? Optional.empty() : Optional.of(traces.getFirst());
        } catch (Exception e) {
            log.warn("轨迹查询失败: traceId={}, error={}", traceId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 查询指定会话的所有轨迹。
     *
     * @param sessionId 会话 ID
     * @return 轨迹记录列表
     */
    public List<TraceRecord> findTracesBySession(String sessionId) {
        try {
            return jdbcTemplate.query(
                    "SELECT * FROM agent_traces WHERE session_id = ? ORDER BY created_at",
                    (rs, rowNum) -> new TraceRecord(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("user_message"),
                            rs.getString("final_output"),
                            rs.getInt("success") == 1,
                            rs.getString("error_message"),
                            rs.getString("termination_reason"),
                            rs.getInt("total_steps"),
                            rs.getInt("total_tokens"),
                            rs.getLong("duration_ms"),
                            rs.getString("created_at")
                    ),
                    sessionId
            );
        } catch (Exception e) {
            log.warn("会话轨迹查询失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 序列化 Action 为 JSON。 */
    private String serializeAction(TraceStep step) {
        try {
            return objectMapper.writeValueAsString(step.action());
        } catch (JsonProcessingException e) {
            log.warn("Action 序列化失败: error={}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 轨迹上下文（内存缓存）。
     *
     * @author zsg
     * @since 2026-07-20
     */
    public static class TraceContext {
        private final String traceId;
        private final String sessionId;
        private final String userMessage;
        private final List<TraceStep> steps;
        private final Instant startTime;
        private @Nullable String finalOutput;
        private boolean success;
        private @Nullable String errorMessage;
        private @Nullable String terminationReason;
        private @Nullable Instant endTime;

        public TraceContext(String traceId, String sessionId, String userMessage,
                           List<TraceStep> steps, Instant startTime) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.userMessage = userMessage;
            this.steps = steps;
            this.startTime = startTime;
        }

        public String traceId() { return traceId; }
        public String sessionId() { return sessionId; }
        public String userMessage() { return userMessage; }
        public List<TraceStep> steps() { return steps; }
        public Instant startTime() { return startTime; }
        public @Nullable String finalOutput() { return finalOutput; }
        public boolean success() { return success; }
        public @Nullable String errorMessage() { return errorMessage; }
        public @Nullable String terminationReason() { return terminationReason; }
        public @Nullable Instant endTime() { return endTime; }

        public void setFinalOutput(@Nullable String finalOutput) { this.finalOutput = finalOutput; }
        public void setSuccess(boolean success) { this.success = success; }
        public void setErrorMessage(@Nullable String errorMessage) { this.errorMessage = errorMessage; }
        public void setTerminationReason(@Nullable String terminationReason) { this.terminationReason = terminationReason; }
        public void setEndTime(@Nullable Instant endTime) { this.endTime = endTime; }
    }

    /**
     * 轨迹查询结果 record。
     *
     * @author zsg
     * @since 2026-07-20
     */
    public record TraceRecord(
            String traceId,
            String sessionId,
            String userMessage,
            @Nullable String finalOutput,
            boolean success,
            @Nullable String errorMessage,
            @Nullable String terminationReason,
            int totalSteps,
            int totalTokens,
            long durationMs,
            String createdAt
    ) {
    }
}
