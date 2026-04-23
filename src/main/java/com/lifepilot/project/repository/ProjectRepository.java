package com.lifepilot.project.repository;

import com.lifepilot.project.model.Project;
import com.lifepilot.project.model.ProjectIsolation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 项目仓储 —— 基于 JdbcTemplate 对 projects 表的 CRUD 封装。
 *
 * <p>所有时间字段以 ISO 8601 文本形式写入 SQLite，枚举字段写入其 name()。
 * 列表查询按 created_at 降序返回，新项目显示在前面。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入项目记录。 */
    public void insert(Project p) {
        jdbcTemplate.update("""
                        INSERT INTO projects (
                            id, name, instructions, isolation, memory_space_id, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                p.id(),
                p.name(),
                p.instructions(),
                p.isolation().name(),
                p.memorySpaceId(),
                p.createdAt().toString(),
                p.updatedAt().toString());
    }

    /** 按 id 查询项目。 */
    public Optional<Project> findById(String id) {
        return jdbcTemplate.query("""
                        SELECT id, name, instructions, isolation, memory_space_id, created_at, updated_at
                          FROM projects
                         WHERE id = ?
                        """,
                this::mapRow,
                id).stream().findFirst();
    }

    /** 返回全部项目，按 created_at 降序。 */
    public List<Project> findAll() {
        return jdbcTemplate.query("""
                        SELECT id, name, instructions, isolation, memory_space_id, created_at, updated_at
                          FROM projects
                         ORDER BY created_at DESC
                        """,
                this::mapRow);
    }

    /** 判断项目名是否已存在（用于创建前的重名校验）。 */
    public boolean existsByName(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE name = ?",
                Integer.class,
                name);
        return count != null && count > 0;
    }

    /** 判断除指定 id 之外是否存在同名项目（用于更新改名时的重名校验）。 */
    public boolean existsByNameAndIdNot(String name, String excludeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE name = ? AND id != ?",
                Integer.class,
                name,
                excludeId);
        return count != null && count > 0;
    }

    /** 更新项目名、指示词、隔离模式与 updated_at；id / memory_space_id / created_at 不可变。 */
    public void update(Project p) {
        jdbcTemplate.update("""
                        UPDATE projects
                           SET name = ?, instructions = ?, isolation = ?, updated_at = ?
                         WHERE id = ?
                        """,
                p.name(),
                p.instructions(),
                p.isolation().name(),
                p.updatedAt().toString(),
                p.id());
    }

    /** 按 id 物理删除。 */
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", id);
    }

    private Project mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("instructions"),
                ProjectIsolation.valueOf(rs.getString("isolation")),
                rs.getString("memory_space_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
