package com.lifepilot.agent.suspend.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
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
import java.util.Map;
import java.util.Optional;

/**
 * 基于 SQLite 的挂起状态持久化实现。
 *
 * <p>使用 JdbcTemplate 操作 suspended_agents 表。
 * {@link #load(String)} 和 {@link #delete(String)} 在同一事务中执行，
 * 防止并发恢复同一个挂起的 Agent。</p>
 *
 * <p>SuspendReason 通过 reason_type（子类型简单名）+ reason_json（序列化 JSON）存储，
 * 恢复时根据 reason_type 确定具体子类型再反序列化。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public class SqliteSuspendStore implements SuspendStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteSuspendStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<SuspendedAgent> rowMapper;

    /** SuspendReason 子类型名 → Class 映射，用于反序列化。 */
    private static final Map<String, Class<? extends SuspendReason>> REASON_TYPE_MAP = Map.of(
            "WorkflowWait", SuspendReason.WorkflowWait.class,
            "UserConfirmation", SuspendReason.UserConfirmation.class,
            "RemoteDelegation", SuspendReason.RemoteDelegation.class,
            "ScheduledWakeup", SuspendReason.ScheduledWakeup.class,
            "ExternalDataWait", SuspendReason.ExternalDataWait.class
    );

    public SqliteSuspendStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    @Override
    public void save(SuspendedAgent agent) {
        String reasonType = agent.suspendReason().getClass().getSimpleName();
        String reasonJson = serializeReason(agent.suspendReason());

        jdbcTemplate.update("""
                INSERT OR REPLACE INTO suspended_agents
                    (trace_id, session_id, channel, reason_type, reason_json,
                     state_json, budget_json, stream_id, suspended_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                agent.traceId(), agent.sessionId(), agent.channel(),
                reasonType, reasonJson,
                agent.stateJson(), agent.budgetJson(), agent.streamId(),
                agent.suspendedAt().toString());

        log.info("挂起状态已保存: traceId={}, reasonType={}", agent.traceId(), reasonType);
    }

    @Override
    @Transactional
    public Optional<SuspendedAgent> load(String traceId) {
        List<SuspendedAgent> results = jdbcTemplate.query(
                "SELECT * FROM suspended_agents WHERE trace_id = ?", rowMapper, traceId);
        return results.stream().findFirst();
    }

    @Override
    public List<SuspendedAgent> findBySession(String sessionId) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM suspended_agents WHERE session_id = ? ORDER BY suspended_at ASC",
                rowMapper, sessionId));
    }

    @Override
    public List<SuspendedAgent> findByReasonType(String reasonType) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM suspended_agents WHERE reason_type = ? ORDER BY suspended_at ASC",
                rowMapper, reasonType));
    }

    @Override
    @Transactional
    public void delete(String traceId) {
        jdbcTemplate.update("DELETE FROM suspended_agents WHERE trace_id = ?", traceId);
        log.debug("挂起状态已删除: traceId={}", traceId);
    }

    @Override
    @Transactional
    public Optional<SuspendedAgent> loadAndDelete(String traceId) {
        List<SuspendedAgent> results = jdbcTemplate.query(
                "SELECT * FROM suspended_agents WHERE trace_id = ?", rowMapper, traceId);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        jdbcTemplate.update("DELETE FROM suspended_agents WHERE trace_id = ?", traceId);
        log.debug("挂起状态已原子加载并删除: traceId={}", traceId);
        return Optional.of(results.getFirst());
    }

    @Override
    public int cleanExpired(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        int deleted = jdbcTemplate.update(
                "DELETE FROM suspended_agents WHERE suspended_at < ?", cutoff.toString());
        if (deleted > 0) {
            log.info("过期挂起记录已清理: 删除数量={}, 截止时间={}", deleted, cutoff);
        }
        return deleted;
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 SuspendedAgent record。 */
    private SuspendedAgent mapRow(ResultSet rs, int rowNum) throws SQLException {
        String reasonType = rs.getString("reason_type");
        String reasonJson = rs.getString("reason_json");
        SuspendReason reason = deserializeReason(reasonType, reasonJson);

        return SuspendedAgent.builder()
                .traceId(rs.getString("trace_id"))
                .sessionId(rs.getString("session_id"))
                .channel(rs.getString("channel"))
                .suspendReason(reason)
                .stateJson(rs.getString("state_json"))
                .suspendedAt(Instant.parse(rs.getString("suspended_at")))
                .streamId(rs.getString("stream_id"))
                .budgetJson(rs.getString("budget_json"))
                .build();
    }

    /** 序列化 SuspendReason 为 JSON 字符串。 */
    private String serializeReason(SuspendReason reason) {
        try {
            return objectMapper.writeValueAsString(reason);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 SuspendReason 失败: " + reason.getClass().getSimpleName(), e);
        }
    }

    /** 根据 reason_type 和 reason_json 反序列化 SuspendReason。 */
    private SuspendReason deserializeReason(String reasonType, String reasonJson) {
        Class<? extends SuspendReason> clazz = REASON_TYPE_MAP.get(reasonType);
        if (clazz == null) {
            throw new IllegalStateException("未知的 SuspendReason 类型: " + reasonType);
        }
        try {
            return objectMapper.readValue(reasonJson, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化 SuspendReason 失败: type=" + reasonType, e);
        }
    }
}
