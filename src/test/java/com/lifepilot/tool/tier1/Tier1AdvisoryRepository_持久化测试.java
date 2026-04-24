package com.lifepilot.tool.tier1;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier1AdvisoryRepository 持久化测试。
 *
 * <p>使用 sqlite in-memory + SingleConnectionDataSource 对齐项目既有 JDBC 集成测试模式
 * （见 QueuedActionRepository_集成测试 / SessionDocumentRepository_持久化测试）。
 * schema 手动建表，与 V15 保持同步，不走 Flyway。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class Tier1AdvisoryRepository_持久化测试 {

    private SingleConnectionDataSource dataSource;
    private Tier1AdvisoryRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        // schema 与 V15__tool_exposure_refactor.sql 中的 tier1_advisory 表定义保持同步
        jdbc.execute("""
                CREATE TABLE tier1_advisory (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    tool_id        TEXT    NOT NULL,
                    advised_at     TEXT    NOT NULL,
                    window_days    INTEGER NOT NULL,
                    coverage_ratio REAL    NOT NULL,
                    status         TEXT    NOT NULL DEFAULT 'PENDING',
                    reviewed_by    TEXT,
                    reviewed_at    TEXT,
                    CONSTRAINT chk_advisory_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
                )
                """);
        repository = new Tier1AdvisoryRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void save_findPending_approve_完整生命周期() {
        Tier1Advisory adv = new Tier1Advisory(null,
                "datastore.query_documents",
                Instant.parse("2026-04-23T10:00:00Z"),
                30, 0.35,
                Tier1AdvisoryStatus.PENDING, null, null);

        Long id = repository.save(adv);
        assertThat(id).isNotNull();

        List<Tier1Advisory> pending = repository.findPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).toolId()).isEqualTo("datastore.query_documents");
        assertThat(pending.get(0).coverageRatio()).isEqualTo(0.35);

        repository.approve(id, "admin");
        assertThat(repository.findPending()).isEmpty();

        List<String> approvedIds = repository.findApprovedToolIds();
        assertThat(approvedIds).containsExactly("datastore.query_documents");
    }

    @Test
    void reject_从pending移除且不出现在approved() {
        Tier1Advisory adv = new Tier1Advisory(null,
                "shell.process",
                Instant.now(), 30, 0.4,
                Tier1AdvisoryStatus.PENDING, null, null);
        Long id = repository.save(adv);

        repository.reject(id, "admin");
        assertThat(repository.findPending()).isEmpty();
        assertThat(repository.findApprovedToolIds()).doesNotContain("shell.process");
    }

    @Test
    void findApprovedToolIds_多条APPROVED全部返回() {
        repository.save(new Tier1Advisory(null, "a.x", Instant.now(), 30, 0.5,
                Tier1AdvisoryStatus.PENDING, null, null));
        repository.save(new Tier1Advisory(null, "b.y", Instant.now(), 30, 0.6,
                Tier1AdvisoryStatus.PENDING, null, null));
        List<Tier1Advisory> pending = repository.findPending();
        repository.approve(pending.get(0).id(), "a");
        repository.approve(pending.get(1).id(), "a");
        assertThat(repository.findApprovedToolIds()).containsExactlyInAnyOrder("a.x", "b.y");
    }
}
