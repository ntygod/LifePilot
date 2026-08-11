package com.lifepilot.interaction.web.repository;

import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnRecord;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话轮次仓储。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Repository
public class ChatTurnRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatTurnRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(String turnId,
                       String sessionId,
                       ChatTurnAction action,
                       ChatTurnStatus status,
                       String requestPayloadJson,
                       Instant now) {
        jdbcTemplate.update("""
                INSERT INTO chat_turns (
                    turn_id, session_id, last_action, status, request_payload_json,
                    attempt_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                turnId, sessionId, action.name(), status.name(), requestPayloadJson,
                1, now.toString(), now.toString()
        );
    }

    public Optional<ChatTurnRecord> findBySessionIdAndTurnId(String sessionId, String turnId) {
        List<ChatTurnRecord> rows = jdbcTemplate.query("""
                SELECT turn_id, session_id, last_action, status, request_payload_json,
                       user_entry_id, assistant_entry_id, latest_trace_id, resumed_from_trace_id,
                       completion_mode, last_error_code, last_error_message, attempt_count,
                       created_at, updated_at
                FROM chat_turns
                WHERE session_id = ? AND turn_id = ?
                """,
                this::mapRow,
                sessionId,
                turnId
        );
        return rows.stream().findFirst();
    }

    public List<ChatTurnRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query("""
                SELECT turn_id, session_id, last_action, status, request_payload_json,
                       user_entry_id, assistant_entry_id, latest_trace_id, resumed_from_trace_id,
                       completion_mode, last_error_code, last_error_message, attempt_count,
                       created_at, updated_at
                FROM chat_turns
                WHERE session_id = ?
                """,
                this::mapRow,
                sessionId
        );
    }

    public void markAttemptStarted(String sessionId,
                                   String turnId,
                                   ChatTurnAction action,
                                   String requestPayloadJson,
                                   Instant now) {
        jdbcTemplate.update("""
                UPDATE chat_turns
                SET last_action = ?,
                    status = ?,
                    request_payload_json = ?,
                    assistant_entry_id = NULL,
                    latest_trace_id = NULL,
                    resumed_from_trace_id = NULL,
                    completion_mode = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    attempt_count = attempt_count + 1,
                    updated_at = ?
                WHERE session_id = ? AND turn_id = ?
                """,
                action.name(), ChatTurnStatus.PENDING.name(), requestPayloadJson,
                now.toString(), sessionId, turnId
        );
    }

    public void bindUserEntry(String sessionId, String turnId, String userEntryId, Instant now) {
        jdbcTemplate.update("""
                UPDATE chat_turns
                SET user_entry_id = ?, updated_at = ?
                WHERE session_id = ? AND turn_id = ?
                """,
                userEntryId, now.toString(), sessionId, turnId
        );
    }

    public void updateTrace(String sessionId, String turnId, @Nullable String traceId, Instant now) {
        jdbcTemplate.update("""
                UPDATE chat_turns
                SET latest_trace_id = ?, updated_at = ?
                WHERE session_id = ? AND turn_id = ?
                """,
                traceId, now.toString(), sessionId, turnId
        );
    }

    public void markCompleted(String sessionId,
                              String turnId,
                              ChatTurnStatus status,
                              @Nullable String assistantEntryId,
                              @Nullable String traceId,
                              @Nullable String resumedFromTraceId,
                              @Nullable String completionMode,
                              Instant now) {
        jdbcTemplate.update("""
                UPDATE chat_turns
                SET status = ?,
                    assistant_entry_id = ?,
                    latest_trace_id = ?,
                    resumed_from_trace_id = ?,
                    completion_mode = ?,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    updated_at = ?
                WHERE session_id = ? AND turn_id = ?
                """,
                status.name(), assistantEntryId, traceId, resumedFromTraceId, completionMode,
                now.toString(), sessionId, turnId
        );
    }

    public void markFailed(String sessionId,
                           String turnId,
                           @Nullable String traceId,
                           @Nullable Integer errorCode,
                           String errorMessage,
                           Instant now) {
        jdbcTemplate.update("""
                UPDATE chat_turns
                SET status = ?,
                    latest_trace_id = ?,
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = ?
                WHERE session_id = ? AND turn_id = ?
                """,
                ChatTurnStatus.FAILED.name(), traceId, errorCode, errorMessage,
                now.toString(), sessionId, turnId
        );
    }

    private ChatTurnRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ChatTurnRecord(
                rs.getString("turn_id"),
                rs.getString("session_id"),
                ChatTurnAction.valueOf(rs.getString("last_action")),
                ChatTurnStatus.valueOf(rs.getString("status")),
                rs.getString("request_payload_json"),
                rs.getString("user_entry_id"),
                rs.getString("assistant_entry_id"),
                rs.getString("latest_trace_id"),
                rs.getString("resumed_from_trace_id"),
                rs.getString("completion_mode"),
                (Integer) rs.getObject("last_error_code"),
                rs.getString("last_error_message"),
                rs.getInt("attempt_count"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
