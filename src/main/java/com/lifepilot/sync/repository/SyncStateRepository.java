package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.SyncState;
import com.lifepilot.sync.model.SyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 同步状态仓储 — 基于 JdbcTemplate 操作 SQLite sync_state 表。
 *
 * <p>提供 SyncState 的查询和 upsert 操作，每个 profileId 对应唯一一条状态记录。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SyncStateRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncStateRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SyncState> rowMapper;

    public SyncStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 根据同步配置 ID 查找同步状态。
     *
     * @param profileId 同步配置 ID
     * @return 同步状态 Optional，不存在时返回 empty
     */
    public Optional<SyncState> findByProfileId(String profileId) {
        List<SyncState> results = jdbcTemplate.query(
                "SELECT * FROM sync_state WHERE profile_id = ?", rowMapper, profileId);
        return results.stream().findFirst();
    }

    /**
     * 插入或更新同步状态。
     *
     * <p>利用 SQLite 的 ON CONFLICT 子句，当 profile_id 唯一索引冲突时执行更新。</p>
     *
     * @param state 同步状态
     */
    public void upsert(SyncState state) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO sync_state
                    (id, profile_id, sync_token, last_sync_at, last_sync_status,
                     last_error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    sync_token = excluded.sync_token,
                    last_sync_at = excluded.last_sync_at,
                    last_sync_status = excluded.last_sync_status,
                    last_error_message = excluded.last_error_message,
                    updated_at = excluded.updated_at
                """,
                state.id(), state.profileId(), state.syncToken(),
                state.lastSyncAt(), state.lastSyncStatus().name(),
                state.lastErrorMessage(),
                state.createdAt() != null ? state.createdAt() : now,
                state.updatedAt() != null ? state.updatedAt() : now);

        log.debug("同步状态 upsert 完成: profileId={}, status={}", state.profileId(), state.lastSyncStatus());
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 SyncState record。 */
    private SyncState mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SyncState(
                rs.getString("id"),
                rs.getString("profile_id"),
                rs.getString("sync_token"),
                rs.getString("last_sync_at"),
                SyncStatus.valueOf(rs.getString("last_sync_status")),
                rs.getString("last_error_message"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
