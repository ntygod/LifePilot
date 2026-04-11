package com.lifepilot.marketplace.install;

import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstalledExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 已安装扩展记录 DAO — 基于 JdbcTemplate 操作 installed_extensions 表。
 *
 * <p>提供已安装市场扩展的 CRUD 操作，按 package_id 唯一键管理记录。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class InstalledExtensionRepository {

    private static final Logger log = LoggerFactory.getLogger(InstalledExtensionRepository.class);

    private static final RowMapper<InstalledExtension> ROW_MAPPER = (rs, rowNum) -> new InstalledExtension(
            rs.getString("id"),
            rs.getString("package_id"),
            ExtensionType.valueOf(rs.getString("type")),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("index_source_url"),
            rs.getString("repo_url"),
            rs.getString("file_path"),
            rs.getString("install_root_path"),
            rs.getString("requirements_json"),
            rs.getString("security_report_json"),
            rs.getString("assets_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public InstalledExtensionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存已安装扩展记录。
     *
     * @param extension 已安装扩展记录
     */
    public void save(InstalledExtension extension) {
        jdbcTemplate.update("""
                INSERT INTO installed_extensions
                    (id, package_id, type, name, version, index_source_url, repo_url,
                     file_path, install_root_path, requirements_json, security_report_json, assets_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                extension.id(), extension.packageId(), extension.type().name(),
                extension.name(), extension.version(), extension.indexSourceUrl(),
                extension.repoUrl(), extension.filePath(), extension.installRootPath(),
                extension.requirementsJson(), extension.securityReportJson(),
                extension.assetsJson(),
                extension.createdAt().toString(), extension.updatedAt().toString());
        log.info("已安装扩展记录保存成功: packageId={}, type={}", extension.packageId(), extension.type());
    }

    /**
     * 按包 ID 查找已安装扩展记录。
     *
     * @param packageId 市场包 ID
     * @return 已安装记录 Optional，不存在时返回 empty
     */
    public Optional<InstalledExtension> findByPackageId(String packageId) {
        List<InstalledExtension> results = jdbcTemplate.query(
                """
                SELECT id, package_id, type, name, version, index_source_url, repo_url,
                       file_path, install_root_path, requirements_json, security_report_json,
                       assets_json, created_at, updated_at
                FROM installed_extensions WHERE package_id = ?
                """,
                ROW_MAPPER, packageId);
        return results.stream().findFirst();
    }

    /**
     * 查询所有已安装扩展记录。
     *
     * @return 所有已安装记录列表
     */
    public List<InstalledExtension> findAll() {
        return List.copyOf(jdbcTemplate.query(
                """
                SELECT id, package_id, type, name, version, index_source_url, repo_url,
                       file_path, install_root_path, requirements_json, security_report_json,
                       assets_json, created_at, updated_at
                FROM installed_extensions ORDER BY created_at ASC
                """,
                ROW_MAPPER));
    }

    /**
     * 按包 ID 删除已安装扩展记录。
     *
     * @param packageId 市场包 ID
     */
    public void deleteByPackageId(String packageId) {
        jdbcTemplate.update("DELETE FROM installed_extensions WHERE package_id = ?", packageId);
        log.info("已安装扩展记录删除成功: packageId={}", packageId);
    }

    /**
     * 更新已安装扩展记录（按 id 匹配）。
     *
     * @param extension 更新后的已安装扩展记录
     */
    public void update(InstalledExtension extension) {
        jdbcTemplate.update("""
                UPDATE installed_extensions SET
                    package_id = ?, type = ?, name = ?, version = ?, index_source_url = ?,
                    repo_url = ?, file_path = ?, install_root_path = ?, requirements_json = ?,
                    security_report_json = ?, assets_json = ?, updated_at = ?
                WHERE id = ?
                """,
                extension.packageId(), extension.type().name(), extension.name(),
                extension.version(), extension.indexSourceUrl(),
                extension.repoUrl(), extension.filePath(), extension.installRootPath(),
                extension.requirementsJson(), extension.securityReportJson(),
                extension.assetsJson(),
                Instant.now().toString(), extension.id());
        log.info("已安装扩展记录更新成功: packageId={}", extension.packageId());
    }
}
