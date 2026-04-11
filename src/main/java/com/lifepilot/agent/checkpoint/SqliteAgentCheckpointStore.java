package com.lifepilot.agent.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 SQLite 的 Agent 检查点存储。
 *
 * @author zsg
 * @since 2026-03-21
 */
public class SqliteAgentCheckpointStore implements AgentCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteAgentCheckpointStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AgentCheckpoint> rowMapper = this::mapRow;

    public SqliteAgentCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AgentCheckpoint checkpoint) {
        jdbcTemplate.update("""
                INSERT INTO agent_checkpoints
                    (session_id, channel, task_fingerprint, source_trace_id,
                     state_json, failure_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id, channel, task_fingerprint) DO UPDATE SET
                    source_trace_id = excluded.source_trace_id,
                    state_json = excluded.state_json,
                    failure_reason = excluded.failure_reason,
                    updated_at = excluded.updated_at
                """,
                checkpoint.sessionId(),
                checkpoint.channel(),
                checkpoint.taskFingerprint(),
                checkpoint.sourceTraceId(),
                checkpoint.stateJson(),
                checkpoint.failureReason(),
                checkpoint.createdAt().toString(),
                checkpoint.updatedAt().toString());
        log.info("Agent 检查点已保存: sessionId={}, traceId={}",
                checkpoint.sessionId(), checkpoint.sourceTraceId());
    }

    @Override
    @Transactional
    public Optional<AgentCheckpoint> claim(String sessionId, String channel, String taskFingerprint) {
        List<AgentCheckpoint> results = jdbcTemplate.query("""
                SELECT session_id, channel, task_fingerprint, source_trace_id,
                       state_json, failure_reason, created_at, updated_at
                FROM agent_checkpoints
                WHERE session_id = ? AND channel = ? AND task_fingerprint = ?
                """, rowMapper, sessionId, channel, taskFingerprint);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        jdbcTemplate.update("""
                DELETE FROM agent_checkpoints
                WHERE session_id = ? AND channel = ? AND task_fingerprint = ?
                """, sessionId, channel, taskFingerprint);
        return Optional.of(results.getFirst());
    }

    @Override
    public void delete(String sessionId, String channel, String taskFingerprint) {
        jdbcTemplate.update("""
                DELETE FROM agent_checkpoints
                WHERE session_id = ? AND channel = ? AND task_fingerprint = ?
                """, sessionId, channel, taskFingerprint);
    }

    @Override
    public int cleanExpired(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        int deleted = jdbcTemplate.update(
                "DELETE FROM agent_checkpoints WHERE updated_at < ?",
                cutoff.toString());
        if (deleted > 0) {
            log.info("过期 Agent 检查点清理完成: deleted={}, cutoff={}", deleted, cutoff);
        }
        return deleted;
    }

    private AgentCheckpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AgentCheckpoint(
                rs.getString("session_id"),
                rs.getString("channel"),
                rs.getString("task_fingerprint"),
                rs.getString("source_trace_id"),
                rs.getString("state_json"),
                rs.getString("failure_reason"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
