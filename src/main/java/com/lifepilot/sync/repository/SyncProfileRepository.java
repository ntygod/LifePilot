package com.lifepilot.sync.repository;

import com.lifepilot.sync.model.ConflictPolicy;
import com.lifepilot.sync.model.SyncDirection;
import com.lifepilot.sync.model.SyncProfile;
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
 * 同步配置仓储 — 基于 JdbcTemplate 操作 SQLite sync_profiles 表。
 *
 * <p>提供 SyncProfile 的 CRUD 操作，支持按启用状态过滤查询。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SyncProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncProfileRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SyncProfile> rowMapper;

    public SyncProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 根据 ID 查找同步配置。
     *
     * @param id 配置 ID
     * @return 同步配置 Optional，不存在时返回 empty
     */
    public Optional<SyncProfile> findById(String id) {
        List<SyncProfile> results = jdbcTemplate.query(
                "SELECT * FROM sync_profiles WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 查询所有同步配置。
     *
     * @return 所有同步配置列表
     */
    public List<SyncProfile> findAll() {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM sync_profiles ORDER BY created_at ASC", rowMapper));
    }

    /**
     * 查询所有已启用的同步配置。
     *
     * @return 已启用的同步配置列表
     */
    public List<SyncProfile> findAllEnabled() {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM sync_profiles WHERE enabled = 1 ORDER BY created_at ASC", rowMapper));
    }

    /**
     * 创建同步配置。
     *
     * @param profile 同步配置（id、createdAt、updatedAt 应由调用方提供）
     */
    public void create(SyncProfile profile) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO sync_profiles
                    (id, name, connector_type, connection_params_json, sync_direction,
                     conflict_policy, cron_expression, enabled, data_type_filter_json,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                profile.id(), profile.name(), profile.connectorType(),
                profile.connectionParamsJson(), profile.syncDirection().name(),
                profile.conflictPolicy().name(), profile.cronExpression(),
                profile.enabled() ? 1 : 0, profile.dataTypeFilterJson(),
                profile.createdAt() != null ? profile.createdAt() : now,
                profile.updatedAt() != null ? profile.updatedAt() : now);

        log.info("同步配置创建成功: id={}, name={}, type={}", profile.id(), profile.name(), profile.connectorType());
    }

    /**
     * 更新同步配置。
     *
     * @param profile 更新后的同步配置
     */
    public void update(SyncProfile profile) {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                UPDATE sync_profiles SET
                    name = ?, connector_type = ?, connection_params_json = ?,
                    sync_direction = ?, conflict_policy = ?, cron_expression = ?,
                    enabled = ?, data_type_filter_json = ?, updated_at = ?
                WHERE id = ?
                """,
                profile.name(), profile.connectorType(), profile.connectionParamsJson(),
                profile.syncDirection().name(), profile.conflictPolicy().name(),
                profile.cronExpression(), profile.enabled() ? 1 : 0,
                profile.dataTypeFilterJson(), now, profile.id());

        log.info("同步配置更新成功: id={}", profile.id());
    }

    /**
     * 删除同步配置。
     *
     * @param id 配置 ID
     */
    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM sync_profiles WHERE id = ?", id);
        log.info("同步配置删除成功: id={}", id);
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 SyncProfile record。 */
    private SyncProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SyncProfile(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("connector_type"),
                rs.getString("connection_params_json"),
                SyncDirection.valueOf(rs.getString("sync_direction")),
                ConflictPolicy.valueOf(rs.getString("conflict_policy")),
                rs.getString("cron_expression"),
                rs.getInt("enabled") == 1,
                rs.getString("data_type_filter_json"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
