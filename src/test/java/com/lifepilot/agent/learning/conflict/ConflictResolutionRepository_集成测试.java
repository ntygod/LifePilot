package com.lifepilot.agent.learning.conflict;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConflictResolutionRepository 集成测试。
 *
 * @author zsg
 * @since 2026-06-23
 */
@DisplayName("ConflictResolutionRepository 集成测试")
class ConflictResolutionRepository_集成测试 {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private ConflictResolutionRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE conflict_resolution_queue (
                    id TEXT PRIMARY KEY,
                    new_entity_id TEXT NOT NULL,
                    candidate_entity_ids TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    verdict TEXT,
                    rationale TEXT,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    resolved_at TEXT
                )
                """);
        repository = new ConflictResolutionRepository(jdbcTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void enqueue_候选列表为空应拒绝写入() {
        assertThatThrownBy(() -> repository.enqueue("new-1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateIds 不能为空");
    }

    @Test
    void enqueue_候选列表包含空值应拒绝写入() {
        assertThatThrownBy(() -> repository.enqueue("new-1", List.of("old-1", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateIds 不能包含空值");
    }

    @Test
    void enqueue_会写入标准化候选Json() {
        String id = repository.enqueue("new-1", List.of(" old-1 ", "old-2"));

        String json = jdbcTemplate.queryForObject(
                "SELECT candidate_entity_ids FROM conflict_resolution_queue WHERE id = ?",
                String.class,
                id);
        assertThat(json).isEqualTo("[\"old-1\",\"old-2\"]");
    }

    @Test
    void findFailedRetriable_候选Json为空应失败() {
        插入失败队列项("queue-1", "");

        assertThatThrownBy(() -> repository.findFailedRetriable(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("候选列表不能为空, id=queue-1");
    }

    @Test
    void findFailedRetriable_候选Json为空数组应失败() {
        插入失败队列项("queue-2", "[]");

        assertThatThrownBy(() -> repository.findFailedRetriable(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("候选列表不能为空, id=queue-2");
    }

    @Test
    void findFailedRetriable_候选Json污染应失败() {
        插入失败队列项("queue-3", "{不是合法JSON");

        assertThatThrownBy(() -> repository.findFailedRetriable(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("候选列表反序列化失败, id=queue-3");
    }

    private void 插入失败队列项(String id, String candidateEntityIds) {
        jdbcTemplate.update("""
                        INSERT INTO conflict_resolution_queue(
                            id, new_entity_id, candidate_entity_ids, status,
                            attempt_count, created_at
                        ) VALUES (?, ?, ?, 'FAILED', 0, ?)
                        """,
                id,
                "new-1",
                candidateEntityIds,
                "2026-06-23T10:00:00Z");
    }
}
