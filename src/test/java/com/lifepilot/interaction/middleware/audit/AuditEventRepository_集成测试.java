package com.lifepilot.interaction.middleware.audit;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.lifepilot.interaction.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditEventRepository 集成测试 — 验证 save/findByFilters CRUD 操作。
 *
 * <p>使用 {@code @SpringBootTest} + Flyway V12 表结构，验证审计事件持久化和过滤查询。
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditEventRepository_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "audit-repo-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "audit-repo-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void save_持久化审计事件() {
        var repo = new AuditEventRepository(jdbcTemplate);
        var event = buildEvent("save-test-1", "user-1", "cli", "agent", 200);

        repo.save(event);

        var count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM gateway_audit_log WHERE audit_id = ?",
                Integer.class, "save-test-1");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void save_TokenUsage字段正确存储() {
        var repo = new AuditEventRepository(jdbcTemplate);
        var usage = new TokenUsage(100, 50, 150, "gpt-4o");
        var event = AuditEvent.builder()
                .auditId("token-test-1")
                .messageId("msg-1")
                .channelType("cli")
                .userId("user-1")
                .responseStatusCode(200)
                .latencyMs(42)
                .tokenUsage(usage)
                .createdAt(Instant.parse("2026-02-25T10:00:00Z"))
                .build();

        repo.save(event);

        var row = jdbcTemplate.queryForMap(
                "SELECT prompt_tokens, completion_tokens, total_tokens, model_id FROM gateway_audit_log WHERE audit_id = ?",
                "token-test-1");
        assertThat(row.get("prompt_tokens")).isEqualTo(100);
        assertThat(row.get("completion_tokens")).isEqualTo(50);
        assertThat(row.get("total_tokens")).isEqualTo(150);
        assertThat(row.get("model_id")).isEqualTo("gpt-4o");
    }

    @Test
    void findByFilters_按userId过滤() {
        var repo = new AuditEventRepository(jdbcTemplate);
        repo.save(buildEvent("filter-u1", "alice", "cli", "agent", 200));
        repo.save(buildEvent("filter-u2", "bob", "cli", "agent", 200));
        repo.save(buildEvent("filter-u3", "alice", "web", "fast_path", 200));

        var results = repo.findByFilters("alice", null, null, null, null);
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> "alice".equals(e.userId()));
    }

    @Test
    void findByFilters_按channelType过滤() {
        var repo = new AuditEventRepository(jdbcTemplate);
        repo.save(buildEvent("filter-c1", "user-ch-unique", "cli", "agent", 200));
        repo.save(buildEvent("filter-c2", "user-ch-unique", "web", "agent", 200));

        var results = repo.findByFilters("user-ch-unique", "web", null, null, null);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().channelType()).isEqualTo("web");
    }

    @Test
    void findByFilters_按routeType过滤() {
        var repo = new AuditEventRepository(jdbcTemplate);
        repo.save(buildEvent("filter-r1", "user-rt-unique", "cli", "fast_path", 200));
        repo.save(buildEvent("filter-r2", "user-rt-unique", "cli", "agent", 200));

        var results = repo.findByFilters("user-rt-unique", null, "fast_path", null, null);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().routeType()).isEqualTo("fast_path");
    }

    @Test
    void findByFilters_按时间范围过滤() {
        var repo = new AuditEventRepository(jdbcTemplate);
        repo.save(buildEventAt("filter-t1", "user-t", Instant.parse("2026-02-20T10:00:00Z")));
        repo.save(buildEventAt("filter-t2", "user-t", Instant.parse("2026-02-22T10:00:00Z")));
        repo.save(buildEventAt("filter-t3", "user-t", Instant.parse("2026-02-25T10:00:00Z")));

        var from = Instant.parse("2026-02-21T00:00:00Z");
        var to = Instant.parse("2026-02-23T00:00:00Z");
        var results = repo.findByFilters(null, null, null, from, to);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().auditId()).isEqualTo("filter-t2");
    }

    @Test
    void findByFilters_组合过滤() {
        var repo = new AuditEventRepository(jdbcTemplate);
        repo.save(buildEvent("combo-1", "combo-user", "cli", "agent", 200));
        repo.save(buildEvent("combo-2", "combo-user", "web", "agent", 200));
        repo.save(buildEvent("combo-3", "other-user", "cli", "agent", 200));

        var results = repo.findByFilters("combo-user", "cli", "agent", null, null);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().auditId()).isEqualTo("combo-1");
    }

    @Test
    void findByFilters_无过滤条件返回全部() {
        var repo = new AuditEventRepository(jdbcTemplate);
        // 清空表以确保测试隔离
        jdbcTemplate.update("DELETE FROM gateway_audit_log");
        repo.save(buildEvent("all-1", "u1", "cli", "agent", 200));
        repo.save(buildEvent("all-2", "u2", "web", "fast_path", 200));

        var results = repo.findByFilters(null, null, null, null, null);
        assertThat(results).hasSize(2);
    }

    @Test
    void findByFilters_结果按createdAt降序排列() {
        var repo = new AuditEventRepository(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM gateway_audit_log");
        repo.save(buildEventAt("order-1", "u-order", Instant.parse("2026-02-20T10:00:00Z")));
        repo.save(buildEventAt("order-2", "u-order", Instant.parse("2026-02-25T10:00:00Z")));
        repo.save(buildEventAt("order-3", "u-order", Instant.parse("2026-02-22T10:00:00Z")));

        var results = repo.findByFilters(null, null, null, null, null);
        assertThat(results).hasSize(3);
        // 降序：最新的在前
        assertThat(results.get(0).auditId()).isEqualTo("order-2");
        assertThat(results.get(1).auditId()).isEqualTo("order-3");
        assertThat(results.get(2).auditId()).isEqualTo("order-1");
    }

    // ---- 辅助方法 ----

    private AuditEvent buildEvent(String auditId, String userId, String channel,
                                   String routeType, int statusCode) {
        return AuditEvent.builder()
                .auditId(auditId)
                .messageId("msg-" + auditId)
                .channelType(channel)
                .userId(userId)
                .responseStatusCode(statusCode)
                .routeType(routeType)
                .latencyMs(100)
                .tokenUsage(TokenUsage.ZERO)
                .createdAt(Instant.now())
                .build();
    }

    private AuditEvent buildEventAt(String auditId, String userId, Instant createdAt) {
        return AuditEvent.builder()
                .auditId(auditId)
                .messageId("msg-" + auditId)
                .channelType("cli")
                .userId(userId)
                .responseStatusCode(200)
                .routeType("agent")
                .latencyMs(100)
                .tokenUsage(TokenUsage.ZERO)
                .createdAt(createdAt)
                .build();
    }
}
