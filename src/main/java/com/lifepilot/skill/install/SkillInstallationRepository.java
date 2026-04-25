package com.lifepilot.skill.install;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SkillInstallation 持久化仓库（skills 表）。
 *
 * <p>负责 skills 表的安装元数据事实源读写：upsert 写入、按名称/启用状态/来源类型查询、
 * 更新启用状态和最后激活时间、按名称删除。所有 SQL 使用 JdbcTemplate 参数化查询。
 * 时间戳遵循项目 SQLite 约定，以 ISO-8601 字符串（{@link Instant#toString()}）存储在
 * TEXT 列中，读取时用 {@link Instant#parse(CharSequence)} 还原。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Repository
public class SkillInstallationRepository {

    private static final String COLUMNS = """
            name, source_type, source_uri, file_path, version,
            enabled, marketplace_id, checksum,
            installed_at, updated_at, last_activated_at
            """;

    private final JdbcTemplate jdbc;

    public SkillInstallationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入或更新一条 Skill 安装记录（按 name 作为冲突键）。
     */
    public void upsert(SkillInstallation s) {
        jdbc.update("""
                INSERT INTO skills (name, source_type, source_uri, file_path, version,
                                    enabled, marketplace_id, checksum,
                                    installed_at, updated_at, last_activated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                  source_type = excluded.source_type,
                  source_uri = excluded.source_uri,
                  file_path = excluded.file_path,
                  version = excluded.version,
                  enabled = excluded.enabled,
                  marketplace_id = excluded.marketplace_id,
                  checksum = excluded.checksum,
                  updated_at = excluded.updated_at,
                  last_activated_at = excluded.last_activated_at
                """,
                s.name(), s.sourceType().name(), s.sourceUri(), s.filePath(), s.version(),
                s.enabled() ? 1 : 0, s.marketplaceId(), s.checksum(),
                s.installedAt().toString(), s.updatedAt().toString(),
                s.lastActivatedAt() == null ? null : s.lastActivatedAt().toString());
    }

    /**
     * 按 name 查找一条 Skill 安装记录。
     */
    public Optional<SkillInstallation> findByName(String name) {
        var list = jdbc.query("SELECT " + COLUMNS + " FROM skills WHERE name = ?",
                (rs, i) -> mapRow(rs), name);
        return list.stream().findFirst();
    }

    /**
     * 按启用状态过滤全部 Skill 安装记录（按 name 升序）。
     */
    public List<SkillInstallation> findAllByEnabled(boolean enabled) {
        return jdbc.query("SELECT " + COLUMNS + " FROM skills WHERE enabled = ? ORDER BY name",
                (rs, i) -> mapRow(rs), enabled ? 1 : 0);
    }

    /**
     * 按来源类型过滤全部 Skill 安装记录（按 name 升序）。
     */
    public List<SkillInstallation> findAllBySourceType(SkillSourceType type) {
        return jdbc.query("SELECT " + COLUMNS + " FROM skills WHERE source_type = ? ORDER BY name",
                (rs, i) -> mapRow(rs), type.name());
    }

    /**
     * 原子更新 enabled 与 updated_at 字段。
     */
    public void setEnabled(String name, boolean enabled) {
        jdbc.update("UPDATE skills SET enabled = ?, updated_at = ? WHERE name = ?",
                enabled ? 1 : 0, Instant.now().toString(), name);
    }

    /**
     * 更新最后激活时间（用于 LRU 淘汰、使用频次统计等）。
     */
    public void updateLastActivatedAt(String name, Instant at) {
        jdbc.update("UPDATE skills SET last_activated_at = ? WHERE name = ?",
                at.toString(), name);
    }

    /**
     * 按 name 删除一条 Skill 安装记录。
     */
    public void delete(String name) {
        jdbc.update("DELETE FROM skills WHERE name = ?", name);
    }

    private SkillInstallation mapRow(ResultSet rs) throws SQLException {
        var lastActivated = rs.getString("last_activated_at");
        return new SkillInstallation(
                rs.getString("name"),
                SkillSourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_uri"),
                rs.getString("file_path"),
                rs.getString("version"),
                rs.getInt("enabled") == 1,
                rs.getString("marketplace_id"),
                rs.getString("checksum"),
                Instant.parse(rs.getString("installed_at")),
                Instant.parse(rs.getString("updated_at")),
                lastActivated == null ? null : Instant.parse(lastActivated)
        );
    }
}
