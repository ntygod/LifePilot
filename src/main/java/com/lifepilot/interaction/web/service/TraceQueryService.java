package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 轨迹查询服务，从 SQLite 读取 Agent 执行轨迹数据。
 *
 * <p>TraceRecorder 仅负责写入，本服务提供读取查询能力。</p>
 *
 * @author zsg
 * @since 2026-02-27
 * @deprecated 请使用 {@link com.lifepilot.observability.trace.TraceQuery}
 */
@Deprecated(forRemoval = true)
public class TraceQueryService {

    private static final Logger log = LoggerFactory.getLogger(TraceQueryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TraceRecord> traceRowMapper;
    private final RowMapper<TraceStepRecord> stepRowMapper;

    public TraceQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.traceRowMapper = this::mapTrace;
        this.stepRowMapper = this::mapStep;
    }

    /**
     * 分页查询轨迹列表，按 created_at 倒序。
     *
     * @param page 页码（从 0 开始）
     * @param size 每页大小
     * @return 分页结果
     */
    public PageResult<TraceRecord> listTraces(int page, int size) {
        long total = Optional.ofNullable(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_traces", Long.class)
        ).orElse(0L);

        int offset = page * size;
        List<TraceRecord> items = jdbcTemplate.query(
                "SELECT * FROM agent_traces ORDER BY created_at DESC LIMIT ? OFFSET ?",
                traceRowMapper, size, offset);

        log.debug("轨迹列表查询: page={}, size={}, total={}, returned={}",
                page, size, total, items.size());
        return new PageResult<>(items, page, size, total);
    }

    /**
     * 查询单条轨迹详情。
     *
     * @param traceId 轨迹 ID
     * @return 轨迹详情，不存在时返回空
     */
    public Optional<TraceRecord> getTrace(String traceId) {
        List<TraceRecord> results = jdbcTemplate.query(
                "SELECT * FROM agent_traces WHERE id = ?",
                traceRowMapper, traceId);
        return results.stream().findFirst();
    }

    /**
     * 查询轨迹的所有步骤，按 step_index 升序。
     *
     * @param traceId 轨迹 ID
     * @return 步骤列表
     */
    public List<TraceStepRecord> getTraceSteps(String traceId) {
        return jdbcTemplate.query(
                "SELECT * FROM agent_trace_steps WHERE trace_id = ? ORDER BY step_index ASC",
                stepRowMapper, traceId);
    }

    // ─────────────────────────────────────────────
    //  RowMapper
    // ─────────────────────────────────────────────

    private TraceRecord mapTrace(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TraceRecord(
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
                rs.getString("model_id"),
                rs.getString("parent_trace_id"),
                rs.getInt("depth"),
                parseInstant(rs.getString("created_at"))
        );
    }

    private TraceStepRecord mapStep(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TraceStepRecord(
                rs.getString("id"),
                rs.getString("trace_id"),
                rs.getInt("step_index"),
                rs.getString("phase_before"),
                rs.getString("phase_after"),
                rs.getString("action_type"),
                rs.getString("action_json"),
                rs.getString("tool_id"),
                rs.getString("tool_input_json"),
                rs.getString("tool_output"),
                rs.getInt("success") == 1,
                rs.getInt("blocked") == 1,
                rs.getString("block_reason"),
                rs.getInt("tokens_used"),
                rs.getLong("latency_ms"),
                parseInstant(rs.getString("created_at"))
        );
    }

    /** 解析 ISO 8601 时间字符串为 Instant。 */
    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
