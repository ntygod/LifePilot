package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.SyncRecord;
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
 * 同步映射仓储 — 基于 JdbcTemplate 操作 SQLite sync_records 表。
 *
 * <p>提供 SyncRecord 的查询、upsert 和删除操作，
 * 支持按 profileId、本地实体映射和远程实体 ID 查询。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SyncRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncRecordRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SyncRecord> rowMapper;

    public SyncRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 查询指定同步配置下的所有同步映射记录。
     *
     * @param profileId 同步配置 ID
     * @return 同步映射记录列表（不可变）
     */
    public List<SyncRecord> findByProfileId(String profileId) {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM sync_records WHERE profile_id = ? ORDER BY created_at ASC",
                rowMapper, profileId));
    }

    /**
     * 根据本地实体映射查找同步记录。
     *
     * @param profileId       同步配置 ID
     * @param localEntityType 本地实体类型
     * @param localEntityId   本地实体 ID
     * @return 同步记录 Optional，不存在时返回 empty
     */
    public Optional<SyncRecord> findByLocalEntity(String profileId, String localEntityType, String localEntityId) {
        List<SyncRecord> results = jdbcTemplate.query(
                "SELECT * FROM sync_records WHERE profile_id = ? AND local_entity_type = ? AND local_entity_id = ?",
                rowMapper, profileId, localEntityType, localEntityId);
        return results.stream().findFirst();
    }

    /**
     * 根据远程实体 ID 查找同步记录。
     *
     * @param profileId      同步配置 ID
     * @param remoteEntityId 远程实体 ID
     * @return 同步记录 Optional，不存在时返回 empty
     */
    public Optional<SyncRecord> findByRemoteEntity(String profileId, String remoteEntityId) {
        List<SyncRecord> results = jdbcTemplate.query(
                "SELECT * FROM sync_records WHERE profile_id = ? AND remote_entity_id = ?",
                rowMapper, profileId, remoteEntityId);
        return results.stream().findFirst();
    }

    /**
     * 插入或更新同步映射记录。
     *
     * <p>利用 SQLite 的 ON CONFLICT 子句，当 (profile_id, local_entity_type, local_entity_id)
     * 唯一索引冲突时执行更新。</p>
     *
     * @param record 同步映射记录
     */
    public void upsert(SyncRecord record) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO sync_records
                    (id, profile_id, local_entity_type, local_entity_id, remote_entity_id,
                     etag, remote_updated_at, last_sync_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id, local_entity_type, local_entity_id) DO UPDATE SET
                    remote_entity_id = excluded.remote_entity_id,
                    etag = excluded.etag,
                    remote_updated_at = excluded.remote_updated_at,
                    last_sync_at = excluded.last_sync_at,
                    updated_at = excluded.updated_at
                """,
                record.id(), record.profileId(), record.localEntityType(),
                record.localEntityId(), record.remoteEntityId(),
                record.etag(), record.remoteUpdatedAt(), record.lastSyncAt(),
                record.createdAt() != null ? record.createdAt() : now,
                record.updatedAt() != null ? record.updatedAt() : now);

        log.debug("同步映射 upsert 完成: profileId={}, localType={}, localId={}, remoteId={}",
                record.profileId(), record.localEntityType(), record.localEntityId(), record.remoteEntityId());
    }

    /**
     * 删除指定同步配置下的所有同步映射记录。
     *
     * @param profileId 同步配置 ID
     */
    public void deleteByProfileId(String profileId) {
        int deleted = jdbcTemplate.update("DELETE FROM sync_records WHERE profile_id = ?", profileId);
        log.info("同步映射批量删除完成: profileId={}, 删除数量={}", profileId, deleted);
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 SyncRecord record。 */
    private SyncRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SyncRecord(
                rs.getString("id"),
                rs.getString("profile_id"),
                rs.getString("local_entity_type"),
                rs.getString("local_entity_id"),
                rs.getString("remote_entity_id"),
                rs.getString("etag"),
                rs.getString("remote_updated_at"),
                rs.getString("last_sync_at"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
