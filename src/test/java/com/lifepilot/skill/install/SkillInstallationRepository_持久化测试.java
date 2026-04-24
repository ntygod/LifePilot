package com.lifepilot.skill.install;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillInstallationRepository 持久化行为测试。
 *
 * <p>使用 {@code @SpringBootTest} + 临时 SQLite + Flyway V17 表结构，验证 skills
 * 表的安装元数据 upsert、按名称/启用状态/来源类型查询以及 setEnabled 更新行为。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillInstallationRepository_持久化测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "skill-install-repo-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "skill-install-repo-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SkillInstallationRepository repository;

    @BeforeEach
    void setUp() {
        // 每个用例清空 skills 表，避免互相污染
        jdbcTemplate.update("DELETE FROM skills");
        repository = new SkillInstallationRepository(jdbcTemplate);
    }

    @Test
    void 应能保存并按名字查找() {
        var install = fixture("github-workflow", SkillSourceType.BUILTIN, true);
        repository.upsert(install);

        var found = repository.findByName("github-workflow");
        assertThat(found).isPresent();
        assertThat(found.get().sourceType()).isEqualTo(SkillSourceType.BUILTIN);
    }

    @Test
    void 按启用状态查询应仅返回enabled的() {
        repository.upsert(fixture("a", SkillSourceType.BUILTIN, true));
        repository.upsert(fixture("b", SkillSourceType.BUILTIN, false));
        repository.upsert(fixture("c", SkillSourceType.BUILTIN, true));

        assertThat(repository.findAllByEnabled(true))
                .extracting(SkillInstallation::name).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void setEnabled应原子更新enabled与updated_at() {
        repository.upsert(fixture("x", SkillSourceType.BUILTIN, true));
        var before = repository.findByName("x").orElseThrow().updatedAt();

        repository.setEnabled("x", false);

        var after = repository.findByName("x").orElseThrow();
        assertThat(after.enabled()).isFalse();
        assertThat(after.updatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void 按来源类型过滤() {
        repository.upsert(fixture("a", SkillSourceType.BUILTIN, true));
        repository.upsert(fixture("b", SkillSourceType.AUTO_GENERATED, true));

        assertThat(repository.findAllBySourceType(SkillSourceType.AUTO_GENERATED))
                .extracting(SkillInstallation::name).containsExactly("b");
    }

    private SkillInstallation fixture(String name, SkillSourceType type, boolean enabled) {
        return new SkillInstallation(name, type, null, "/p/" + name, "1.0.0",
                enabled, null, "sha", Instant.now(), Instant.now(), null);
    }
}
