package com.lifepilot.marketplace.config;

import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Marketplace 安装资产迁移集成测试。
 *
 * <p>验证 {@code installed_extensions} 已具备目录化安装所需字段。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
@SpringBootTest(classes = MarketplaceMigrationTest.TestApp.class)
@ActiveProfiles("test")
@EnableConfigurationProperties(ToolConfigProperties.class)
class MarketplaceMigrationTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
    })
    @Import(com.lifepilot.config.DataSourceConfig.class)
    static class TestApp {
    }

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-marketplace-migration-test-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-marketplace-migration-vec-test-" + DB_ID + ".db")
                .toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void installedExtensions_包含安装根目录和资产字段() {
        var columns = jdbcTemplate.query(
                "PRAGMA table_info(installed_extensions)",
                (rs, rowNum) -> rs.getString("name")
        );

        assertThat(columns)
                .contains("install_root_path")
                .contains("assets_json");
    }

    @Test
    void installedExtensions_可写入安装目录和资产Json() {
        jdbcTemplate.update("""
                INSERT INTO installed_extensions (
                    id, package_id, type, name, version, index_source_url, repo_url,
                    file_path, install_root_path, requirements_json, security_report_json,
                    assets_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "ext-test",
                "channel.test",
                "CHANNEL",
                "测试渠道",
                "1.0.0",
                "https://example.com/index.json",
                "https://example.com/repo",
                "D:/plugins/channel.test/channel-plugin.json",
                "D:/plugins/channel.test",
                "[]",
                "{}",
                "[{\"kind\":\"README\",\"relativePath\":\"docs/README.md\",\"localPath\":\"D:/plugins/channel.test/docs/README.md\"}]",
                "2026-03-29T12:00:00Z",
                "2026-03-29T12:00:00Z"
        );

        var installRootPath = jdbcTemplate.queryForObject(
                "SELECT install_root_path FROM installed_extensions WHERE package_id = ?",
                String.class,
                "channel.test"
        );
        var assetsJson = jdbcTemplate.queryForObject(
                "SELECT assets_json FROM installed_extensions WHERE package_id = ?",
                String.class,
                "channel.test"
        );

        assertThat(installRootPath).isEqualTo("D:/plugins/channel.test");
        assertThat(assetsJson).contains("\"README\"");
    }
}
