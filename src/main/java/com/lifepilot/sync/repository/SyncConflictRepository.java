package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.SyncConflict;
import com.lifepilot.sync.model.SyncConflict.ConflictStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 同步冲突仓储 — 基于 JdbcTemplate 操作 SQLite sync_conflicts 表。
 *
 * <p>提供 SyncConflict 的查询、创建和解决操作，支持按 profileId 过滤未解决冲突。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SyncConflictRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncConflictRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SyncConflict> rowMapper;

    public SyncConflictRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 查询指定同步配置下所有未解决的冲突。
     *
     * @param profileId 同步配置 ID
     * @return 未解决冲突列表（不可变）
     */
    public List<SyncConflict> findUnresolvedByProfileId(String profileId) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM sync_conflicts WHERE profile_id = ? AND status = 'UNRESOLVED' ORDER BY created_at ASC",
                rowMapper, profileId));
    }

    /**
     * 创建同步冲突记录。
     *
     * @param conflict 同步冲突（id、createdAt 应由调用方提供）
     */
    public void create(SyncConflict conflict) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO sync_conflicts
                    (id, profile_id, local_entity_type, local_entity_id,
                     local_snapshot_json, remote_snapshot_json, status, resolved_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                conflict.id(), conflict.profileId(), conflict.localEntityType(),
                conflict.localEntityId(), conflict.localSnapshotJson(),
                conflict.remoteSnapshotJson(), conflict.status().name(),
                conflict.resolvedAt(),
                conflict.createdAt() != null ? conflict.createdAt() : now);

        log.info("同步冲突创建: id={}, profileId={}, entityType={}, entityId={}",
                conflict.id(), conflict.profileId(), conflict.localEntityType(), conflict.localEntityId());
    }

    /**
     * 将冲突标记为已解决，同时记录解决时间。
     *
     * @param conflictId 冲突 ID
     */
    public void resolve(String conflictId) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE sync_conflicts SET status = ?, resolved_at = ? WHERE id = ?",
                ConflictStatus.RESOLVED.name(), now, conflictId);

        log.info("同步冲突已解决: id={}", conflictId);
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 SyncConflict record。 */
    private SyncConflict mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SyncConflict(
                rs.getString("id"),
                rs.getString("profile_id"),
                rs.getString("local_entity_type"),
                rs.getString("local_entity_id"),
                rs.getString("local_snapshot_json"),
                rs.getString("remote_snapshot_json"),
                ConflictStatus.valueOf(rs.getString("status")),
                rs.getString("resolved_at"),
                rs.getString("created_at")
        );
    }
}
