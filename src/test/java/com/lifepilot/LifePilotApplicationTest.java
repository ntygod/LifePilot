package com.lifepilot;

import com.lifepilot.config.DataSourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ZhiWei 应用启动验证集成测试。
 *
 * <p>验证项目骨架配置正确：Spring 上下文加载、健康检查端点、
 * Flyway 迁移执行、SQLite PRAGMA 设置。</p>
 *
 * <p>使用内部 {@link TestApp} 配置类，仅启用 {@code @EnableAutoConfiguration}
 * 而不使用 {@code @ComponentScan}。所有 AutoConfiguration 类通过
 * {@code META-INF/spring/AutoConfiguration.imports} 注册，并由
 * {@code application-test.yml} 中的 {@code enabled=false} 条件守卫。
 * 这样避免了 {@code @Component} / {@code @RestController} 被组件扫描
 * 拾取后因依赖缺失而导致启动失败的问题。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@SpringBootTest(classes = LifePilotApplicationTest.TestApp.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LifePilotApplicationTest {

    /**
     * 测试专用启动配置 — 仅启用自动配置，不做组件扫描。
     *
     * <p>生产环境使用 {@link LifePilotApplication}（含 {@code @ComponentScan}），
     * 此处刻意省略以避免拾取依赖被禁用模块的 {@code @Component} 类。</p>
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
            JdbcRepositoriesAutoConfiguration.class,
            DataSourceAutoConfiguration.class
    })
    @Import(DataSourceConfig.class)
    static class TestApp {
    }

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void 应用上下文_正常加载() {
        // Spring 上下文加载成功即通过，无需额外断言
        assertThat(dataSource).isNotNull();
        assertThat(jdbcTemplate).isNotNull();
    }

    @Test
    void 健康检查端点_返回UP状态() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").exists());
    }

    @Test
    void Flyway迁移_成功执行() {
        // 验证 V1 迁移创建的核心表存在
        var count = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM sqlite_master
                WHERE type='table'
                  AND name IN (
                    'model_services',
                    'model_service_vendor_templates',
                    'model_service_model_presets',
                    'generation_settings',
                    'embedding_settings',
                    'rerank_settings',
                    'execution_grants',
                    'permission_decisions'
                  )
                """,
                Integer.class);
        assertThat(count).isEqualTo(8);

        var modelServiceCount = jdbcTemplate.queryForObject("SELECT count(*) FROM model_services", Integer.class);
        var vendorTemplateCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_service_vendor_templates",
                Integer.class);
        var modelPresetCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_service_model_presets",
                Integer.class);
        var generationSettingsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM generation_settings", Integer.class);
        var embeddingSettingsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM embedding_settings", Integer.class);
        var rerankSettingsCount = jdbcTemplate.queryForObject("SELECT count(*) FROM rerank_settings", Integer.class);
        var executionGrantCount = jdbcTemplate.queryForObject("SELECT count(*) FROM execution_grants", Integer.class);
        var permissionDecisionCount = jdbcTemplate.queryForObject("SELECT count(*) FROM permission_decisions", Integer.class);
        var generationDefaultServiceId = jdbcTemplate.queryForObject(
                "SELECT default_service_id FROM generation_settings WHERE id = 'default'",
                String.class);
        var embeddingDefaultServiceId = jdbcTemplate.queryForObject(
                "SELECT default_service_id FROM embedding_settings WHERE id = 'default'",
                String.class);
        var rerankLlmServiceId = jdbcTemplate.queryForObject(
                "SELECT llm_service_id FROM rerank_settings WHERE id = 'default'",
                String.class);
        assertThat(modelServiceCount).isZero();
        assertThat(vendorTemplateCount).isEqualTo(7);
        assertThat(modelPresetCount).isGreaterThanOrEqualTo(16);
        assertThat(generationSettingsCount).isEqualTo(1);
        assertThat(embeddingSettingsCount).isEqualTo(1);
        assertThat(rerankSettingsCount).isEqualTo(1);
        assertThat(executionGrantCount).isEqualTo(0);
        assertThat(permissionDecisionCount).isEqualTo(0);
        assertThat(generationDefaultServiceId).isNull();
        assertThat(embeddingDefaultServiceId).isNull();
        assertThat(rerankLlmServiceId).isNull();
    }

    @Test
    void SQLite连接_PRAGMA设置正确() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            var stmt = conn.createStatement();

            // 验证 journal_mode=WAL
            var rs = stmt.executeQuery("PRAGMA journal_mode");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");

            // 验证 synchronous=NORMAL (1)
            rs = stmt.executeQuery("PRAGMA synchronous");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);

            // 验证 foreign_keys=ON (1)
            rs = stmt.executeQuery("PRAGMA foreign_keys");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);

            // 验证 busy_timeout=30000（application.yml 中配置）
            rs = stmt.executeQuery("PRAGMA busy_timeout");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(30000);
        }
    }
}
