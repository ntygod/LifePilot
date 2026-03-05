package com.lifepilot.skill.marketplace.install;

import com.lifepilot.skill.marketplace.model.InstalledSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InstalledSkillRepository 单元测试。
 *
 * <p>使用内存 SQLite 直接构建 JdbcTemplate，无需启动 Spring 上下文。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class InstalledSkillRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private InstalledSkillRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        // 创建表结构
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS installed_skills (
                    id                  TEXT PRIMARY KEY,
                    package_id          TEXT NOT NULL UNIQUE,
                    name                TEXT NOT NULL,
                    version             TEXT NOT NULL,
                    index_source_url    TEXT NOT NULL,
                    repo_url            TEXT NOT NULL,
                    file_path           TEXT NOT NULL,
                    security_report_json TEXT,
                    created_at          TEXT NOT NULL,
                    updated_at          TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("DELETE FROM installed_skills");
        repository = new InstalledSkillRepository(jdbcTemplate);
    }

    @Test
    void save_findByPackageId_往返一致() {
        var skill = createTestSkill("pkg-todo", "Todo 助手", "1.0.0");

        repository.save(skill);
        var found = repository.findByPackageId("pkg-todo");

        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.id()).isEqualTo(skill.id());
        assertThat(result.packageId()).isEqualTo("pkg-todo");
        assertThat(result.name()).isEqualTo("Todo 助手");
        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.indexSourceUrl()).isEqualTo("https://example.com/index.json");
        assertThat(result.repoUrl()).isEqualTo("https://github.com/test/repo");
        assertThat(result.filePath()).isEqualTo("skills/pkg-todo.yaml");
        assertThat(result.securityReportJson()).isEqualTo("{\"overallRisk\":\"LOW\"}");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.updatedAt()).isNotNull();
    }

    @Test
    void save_securityReportJson为null_正常保存() {
        var skill = new InstalledSkill(
                UUID.randomUUID().toString(), "pkg-null-report", "测试",
                "1.0.0", "https://example.com/index.json",
                "https://github.com/test/repo", "skills/test.yaml",
                null, Instant.now(), Instant.now()
        );

        repository.save(skill);
        var found = repository.findByPackageId("pkg-null-report");

        assertThat(found).isPresent();
        assertThat(found.get().securityReportJson()).isNull();
    }

    @Test
    void findByPackageId_不存在_返回empty() {
        assertThat(repository.findByPackageId("nonexistent")).isEmpty();
    }

    @Test
    void findAll_返回所有记录() {
        repository.save(createTestSkill("pkg-a", "Skill A", "1.0.0"));
        repository.save(createTestSkill("pkg-b", "Skill B", "2.0.0"));
        repository.save(createTestSkill("pkg-c", "Skill C", "1.2.0"));

        var all = repository.findAll();
        assertThat(all).hasSize(3);
    }

    @Test
    void findAll_空表_返回空列表() {
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAll_返回不可变列表() {
        repository.save(createTestSkill("pkg-a", "Skill A", "1.0.0"));
        var all = repository.findAll();
        assertThat(all).isUnmodifiable();
    }

    @Test
    void deleteByPackageId_删除已有记录() {
        repository.save(createTestSkill("pkg-del", "待删除", "1.0.0"));
        assertThat(repository.findByPackageId("pkg-del")).isPresent();

        repository.deleteByPackageId("pkg-del");
        assertThat(repository.findByPackageId("pkg-del")).isEmpty();
    }

    @Test
    void deleteByPackageId_不存在的id_不抛异常() {
        repository.deleteByPackageId("nonexistent");
    }

    @Test
    void deleteByPackageId_不影响其他记录() {
        repository.save(createTestSkill("pkg-a", "Skill A", "1.0.0"));
        repository.save(createTestSkill("pkg-b", "Skill B", "1.0.0"));

        repository.deleteByPackageId("pkg-a");

        assertThat(repository.findByPackageId("pkg-a")).isEmpty();
        assertThat(repository.findByPackageId("pkg-b")).isPresent();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void update_修改版本和名称() {
        var skill = createTestSkill("pkg-upd", "原始名称", "1.0.0");
        repository.save(skill);

        var updated = new InstalledSkill(
                skill.id(), skill.packageId(), "更新后名称", "2.0.0",
                skill.indexSourceUrl(), skill.repoUrl(), skill.filePath(),
                skill.securityReportJson(), skill.createdAt(), skill.updatedAt()
        );
        repository.update(updated);

        var found = repository.findByPackageId("pkg-upd").orElseThrow();
        assertThat(found.name()).isEqualTo("更新后名称");
        assertThat(found.version()).isEqualTo("2.0.0");
        // updatedAt 应该被更新（update 方法内部使用 Instant.now()）
        assertThat(found.updatedAt()).isNotEqualTo(skill.updatedAt());
    }

    // ---- 辅助方法 ----

    private InstalledSkill createTestSkill(String packageId, String name, String version) {
        return new InstalledSkill(
                UUID.randomUUID().toString(),
                packageId,
                name,
                version,
                "https://example.com/index.json",
                "https://github.com/test/repo",
                "skills/" + packageId + ".yaml",
                "{\"overallRisk\":\"LOW\"}",
                Instant.now(),
                Instant.now()
        );
    }
}
