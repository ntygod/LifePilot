package com.lifepilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * LifePilot 应用启动验证集成测试。
 *
 * <p>验证项目骨架配置正确：Spring 上下文加载、健康检查端点、
 * Flyway 迁移执行、SQLite PRAGMA 设置。
 *
 * <p>使用 {@link DynamicPropertySource} 为每次测试运行生成唯一的临时数据库文件，
 * 避免残留数据库文件导致 Flyway 迁移冲突。
 *
 * @author zsg
 * @since 2026-02-24
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LifePilotApplicationTest {

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
        var result = jdbcTemplate.queryForObject(
                "SELECT id FROM schema_version_check WHERE id = 'init'",
                String.class);
        assertThat(result).isEqualTo("init");
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

            // 验证 busy_timeout=5000
            rs = stmt.executeQuery("PRAGMA busy_timeout");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(5000);
        }
    }
}
