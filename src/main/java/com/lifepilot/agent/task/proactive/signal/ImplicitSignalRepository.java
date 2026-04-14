package com.lifepilot.agent.task.proactive.signal;

import com.lifepilot.agent.task.proactive.SafeEnum;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

/**
 * 隐式信号仓储。
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ImplicitSignalRepository {

    private final JdbcTemplate jdbc;

    public ImplicitSignalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<ImplicitSignal> ROW_MAPPER = (rs, _) -> new ImplicitSignal(
            rs.getString("id"), rs.getString("user_id"), rs.getString("notification_id"),
            SafeEnum.parse(ImplicitSignalType.class, rs.getString("signal_type"), ImplicitSignalType.POST_DELIVERY_IGNORE),
            rs.getString("behavior_name"), rs.getString("topic_key"),
            rs.getFloat("signal_value"), rs.getString("evidence"),
            Instant.parse(rs.getString("created_at")));

    public void save(ImplicitSignal signal) {
        jdbc.update("""
                INSERT INTO proactive_implicit_signals
                (id, user_id, notification_id, signal_type, behavior_name, topic_key,
                 signal_value, evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, signal.id(), signal.userId(), signal.notificationId(),
                signal.signalType().name(), signal.behaviorName(), signal.topicKey(),
                signal.signalValue(), signal.evidence(), signal.createdAt().toString());
    }

    public List<ImplicitSignal> findRecentByUserId(String userId, int limit) {
        return jdbc.query("""
                SELECT id, user_id, notification_id, signal_type, behavior_name, topic_key,
                       signal_value, evidence, created_at
                FROM proactive_implicit_signals
                WHERE user_id = ? ORDER BY created_at DESC LIMIT ?
                """, ROW_MAPPER, userId, limit);
    }
}
