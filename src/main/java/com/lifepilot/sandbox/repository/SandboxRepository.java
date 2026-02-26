package com.lifepilot.sandbox.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import com.lifepilot.sandbox.model.ExecutionRecord;
import com.lifepilot.sandbox.model.Language;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.NonNull;

/**
 * 沙箱执行审计持久化仓储。
 *
 * <p>管理 sandbox_executions 表的 CRUD 操作，代码内容仅存 SHA-256 哈希，
 * stdout/stderr 仅存字节长度，不存储原始内容。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class SandboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ExecutionRecord> rowMapper = new ExecutionRecordRowMapper();

    public SandboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入执行审计记录。
     *
     * @param record 执行记录（codeHash 已为 SHA-256，stdoutLength/stderrLength 已为字节长度）
     */
    public void insert(ExecutionRecord record) {
        jdbcTemplate.update("""
            INSERT INTO sandbox_executions
            (id, session_id, language, code_hash, code_length, booter_type,
             validation_passed, violation_count, exit_code, stdout_length,
             stderr_length, duration_ms, state, error_message, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            record.id(),
            record.sessionId(),
            record.language().name(),
            record.codeHash(),
            record.codeLength(),
            record.booterType(),
            record.validationPassed() ? 1 : 0,
            record.violationCount(),
            record.exitCode(),
            record.stdoutLength(),
            record.stderrLength(),
            record.durationMs(),
            record.state(),
            record.errorMessage(),
            record.createdAt().toString(),
            record.updatedAt().toString()
        );
    }

    /**
     * 按会话 ID 查询执行记录。
     *
     * @param sessionId 会话 ID
     * @return 匹配的执行记录列表，按创建时间降序
     */
    public List<ExecutionRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
            "SELECT * FROM sandbox_executions WHERE session_id = ? ORDER BY created_at DESC",
            rowMapper, sessionId);
    }

    /**
     * 按执行状态查询执行记录。
     *
     * @param state 执行状态（COMPLETED / TIMEOUT / FAILED / REJECTED）
     * @return 匹配的执行记录列表，按创建时间降序
     */
    public List<ExecutionRecord> findByState(String state) {
        return jdbcTemplate.query(
            "SELECT * FROM sandbox_executions WHERE state = ? ORDER BY created_at DESC",
            rowMapper, state);
    }

    /**
     * 按时间范围查询执行记录。
     *
     * @param from 起始时间（含）
     * @param to   结束时间（含）
     * @return 匹配的执行记录列表，按创建时间降序
     */
    public List<ExecutionRecord> findByTimeRange(Instant from, Instant to) {
        return jdbcTemplate.query(
            "SELECT * FROM sandbox_executions WHERE created_at BETWEEN ? AND ? ORDER BY created_at DESC",
            rowMapper, from.toString(), to.toString());
    }

    /**
     * ExecutionRecord 行映射器。
     */
    private static class ExecutionRecordRowMapper implements RowMapper<ExecutionRecord> {

        @Override
        public ExecutionRecord mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            // 处理 @Nullable Integer / Long 字段：getObject 返回 null 当列值为 NULL
            Integer exitCode = rs.getObject("exit_code", Integer.class);
            Integer stdoutLength = rs.getObject("stdout_length", Integer.class);
            Integer stderrLength = rs.getObject("stderr_length", Integer.class);
            Long durationMs = rs.getObject("duration_ms", Long.class);

            return new ExecutionRecord(
                rs.getString("id"),
                rs.getString("session_id"),
                Language.valueOf(rs.getString("language")),
                rs.getString("code_hash"),
                rs.getInt("code_length"),
                rs.getString("booter_type"),
                rs.getInt("validation_passed") == 1,
                rs.getInt("violation_count"),
                exitCode,
                stdoutLength,
                stderrLength,
                durationMs,
                rs.getString("state"),
                rs.getString("error_message"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
            );
        }
    }
}
