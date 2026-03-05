package com.lifepilot.skill.marketplace.install;

import com.lifepilot.skill.marketplace.model.InstalledSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 已安装 Skill 记录 DAO — 基于 JdbcTemplate 操作 installed_skills 表。
 *
 * <p>提供已安装市场 Skill 的 CRUD 操作，按 package_id 唯一键管理记录。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class InstalledSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(InstalledSkillRepository.class);

    private static final RowMapper<InstalledSkill> ROW_MAPPER = (rs, rowNum) -> new InstalledSkill(
            rs.getString("id"),
            rs.getString("package_id"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("index_source_url"),
            rs.getString("repo_url"),
            rs.getString("file_path"),
            rs.getString("security_report_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public InstalledSkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存已安装 Skill 记录。
     *
     * @param skill 已安装 Skill 记录
     */
    public void save(InstalledSkill skill) {
        jdbcTemplate.update("""
                INSERT INTO installed_skills
                    (id, package_id, name, version, index_source_url, repo_url,
                     file_path, security_report_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                skill.id(), skill.packageId(), skill.name(), skill.version(),
                skill.indexSourceUrl(), skill.repoUrl(), skill.filePath(),
                skill.securityReportJson(),
                skill.createdAt().toString(), skill.updatedAt().toString());
        log.info("已安装 Skill 记录保存成功: packageId={}, name={}", skill.packageId(), skill.name());
    }

    /**
     * 按包 ID 查找已安装 Skill 记录。
     *
     * @param packageId 市场包 ID
     * @return 已安装记录 Optional，不存在时返回 empty
     */
    public Optional<InstalledSkill> findByPackageId(String packageId) {
        List<InstalledSkill> results = jdbcTemplate.query(
                "SELECT * FROM installed_skills WHERE package_id = ?",
                ROW_MAPPER, packageId);
        return results.stream().findFirst();
    }

    /**
     * 查询所有已安装 Skill 记录。
     *
     * @return 所有已安装记录列表
     */
    public List<InstalledSkill> findAll() {
        return List.copyOf(jdbcTemplate.query(
                "SELECT * FROM installed_skills ORDER BY created_at ASC",
                ROW_MAPPER));
    }

    /**
     * 按包 ID 删除已安装 Skill 记录。
     *
     * @param packageId 市场包 ID
     */
    public void deleteByPackageId(String packageId) {
        jdbcTemplate.update("DELETE FROM installed_skills WHERE package_id = ?", packageId);
        log.info("已安装 Skill 记录删除成功: packageId={}", packageId);
    }

    /**
     * 更新已安装 Skill 记录（按 id 匹配）。
     *
     * @param skill 更新后的已安装 Skill 记录
     */
    public void update(InstalledSkill skill) {
        jdbcTemplate.update("""
                UPDATE installed_skills SET
                    package_id = ?, name = ?, version = ?, index_source_url = ?,
                    repo_url = ?, file_path = ?, security_report_json = ?, updated_at = ?
                WHERE id = ?
                """,
                skill.packageId(), skill.name(), skill.version(), skill.indexSourceUrl(),
                skill.repoUrl(), skill.filePath(), skill.securityReportJson(),
                Instant.now().toString(), skill.id());
        log.info("已安装 Skill 记录更新成功: packageId={}", skill.packageId());
    }
}
