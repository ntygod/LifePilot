package com.lifepilot.agent.task.reminder.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一追踪注册中心 — 管理快递/航班/行程等外部追踪条目。
 *
 * <p>接受来自对话提取、剪贴板捕获、短信转发等渠道的注册请求，
 * 持久化到数据库，供定时轮询和信号采集使用。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
public class TrackingRegistry {

    private static final Logger log = LoggerFactory.getLogger(TrackingRegistry.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TrackingEntry> ROW_MAPPER = (rs, _) -> new TrackingEntry(
            rs.getString("id"),
            rs.getString("user_id"),
            TrackingType.valueOf(rs.getString("type")),
            rs.getString("tracking_key"),
            rs.getString("title"),
            rs.getString("status"),
            parseInstant(rs.getString("last_checked_at")),
            parseInstant(rs.getString("relevant_at")),
            rs.getString("metadata"),
            rs.getInt("active") == 1,
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public TrackingRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 注册新的追踪条目。如果同类型 + 同标识已存在，则更新。
     */
    public String register(String userId, TrackingType type, String trackingKey,
                           String title, @Nullable Instant relevantAt) {
        Instant now = Instant.now();
        Optional<TrackingEntry> existing = findByTypeAndKey(userId, type, trackingKey);
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    UPDATE tracking_entries SET title = ?, relevant_at = ?, active = 1, updated_at = ?
                    WHERE id = ?
                    """, title, relevantAt != null ? relevantAt.toString() : null, now.toString(),
                    existing.get().id());
            log.debug("追踪条目更新: type={}, key={}", type, trackingKey);
            return existing.get().id();
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO tracking_entries (
                    id, user_id, type, tracking_key, title, status,
                    last_checked_at, relevant_at, metadata, active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """,
                id, userId, type.name(), trackingKey, title, null,
                null, relevantAt != null ? relevantAt.toString() : null, null,
                now.toString(), now.toString()
        );
        log.info("追踪条目注册: type={}, key={}, title={}", type, trackingKey, title);
        return id;
    }

    /**
     * 查询需要轮询更新的活跃条目。
     */
    public List<TrackingEntry> findActiveByType(TrackingType type) {
        return jdbcTemplate.query("""
                SELECT id, user_id, type, tracking_key, title, status,
                           last_checked_at, relevant_at, metadata, active, created_at, updated_at
                    FROM tracking_entries
                WHERE type = ? AND active = 1
                ORDER BY created_at DESC
                """, ROW_MAPPER, type.name());
    }

    /**
     * 查询用户的所有活跃追踪条目。
     */
    public List<TrackingEntry> findActiveByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, type, tracking_key, title, status,
                           last_checked_at, relevant_at, metadata, active, created_at, updated_at
                    FROM tracking_entries
                WHERE user_id = ? AND active = 1
                ORDER BY created_at DESC
                """, ROW_MAPPER, userId);
    }

    /**
     * 更新追踪条目状态。
     */
    public void updateStatus(String id, String status, @Nullable String metadata) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE tracking_entries
                SET status = ?, metadata = COALESCE(?, metadata), last_checked_at = ?, updated_at = ?
                WHERE id = ?
                """, status, metadata, now.toString(), now.toString(), id);
    }

    /**
     * 标记追踪条目为不活跃（已签收/已出行等）。
     */
    public void deactivate(String id) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE tracking_entries SET active = 0, updated_at = ? WHERE id = ?
                """, now.toString(), id);
    }

    private Optional<TrackingEntry> findByTypeAndKey(String userId, TrackingType type, String trackingKey) {
        List<TrackingEntry> entries = jdbcTemplate.query("""
                SELECT id, user_id, type, tracking_key, title, status,
                           last_checked_at, relevant_at, metadata, active, created_at, updated_at
                    FROM tracking_entries
                WHERE user_id = ? AND type = ? AND tracking_key = ?
                LIMIT 1
                """, ROW_MAPPER, userId, type.name(), trackingKey);
        return entries.stream().findFirst();
    }

    @Nullable
    private static Instant parseInstant(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
