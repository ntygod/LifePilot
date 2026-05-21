package com.lifepilot.memory.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatTurnMemorySnapshotRepository 项目空间字段（V18）集成测试 ——
 * 验证 project_space_id 列的 insert / upsert / 回读与 null 默认语义。
 *
 * <p>采用内存 SQLite + 手动建表策略，与同目录 MemorySpaceRepository_项目空间集成测试保持一致，
 * 不依赖 Flyway 与完整 Spring 上下文。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ChatTurnMemorySnapshotRepository_项目空间字段测试 {

    private ChatTurnMemorySnapshotRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // V1 chat_turn_memory_snapshots 结构 + V18 新增 project_space_id 列
        jdbc.execute("""
                CREATE TABLE chat_turn_memory_snapshots (
                    turn_id                          TEXT PRIMARY KEY,
                    session_id                       TEXT NOT NULL,
                    personal_space_id                TEXT,
                    experience_space_id              TEXT,
                    domain_write_space_id            TEXT,
                    project_space_id                 TEXT,
                    read_space_ids_json              TEXT NOT NULL DEFAULT '[]',
                    effective_knowledge_base_ids_json TEXT NOT NULL DEFAULT '[]',
                    personal_learning_enabled        INTEGER NOT NULL DEFAULT 1,
                    domain_learning_enabled          INTEGER NOT NULL DEFAULT 0,
                    experience_learning_enabled      INTEGER NOT NULL DEFAULT 1,
                    resolution_source_json           TEXT NOT NULL DEFAULT '{}',
                    created_at                       TEXT NOT NULL
                )""");
        repository = new ChatTurnMemorySnapshotRepository(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void 保存带项目空间的快照_回读字段一致() {
        var snapshot = newSnapshot("turn-1", "project-space-abc");

        repository.save(snapshot);

        var found = repository.findByTurnId("turn-1");
        assertThat(found).isPresent();
        assertThat(found.get().projectSpaceId()).isEqualTo("project-space-abc");
    }

    @Test
    void 保存主账户对话快照_项目空间字段为null() {
        var snapshot = newSnapshot("turn-2", null);

        repository.save(snapshot);

        var found = repository.findByTurnId("turn-2");
        assertThat(found).isPresent();
        assertThat(found.get().projectSpaceId()).isNull();
    }

    @Test
    void 相同turnId二次保存_项目空间字段被覆盖更新() {
        repository.save(newSnapshot("turn-3", null));
        repository.save(newSnapshot("turn-3", "project-space-xyz"));

        var found = repository.findByTurnId("turn-3");
        assertThat(found).isPresent();
        assertThat(found.get().projectSpaceId()).isEqualTo("project-space-xyz");
    }

    private ChatTurnMemorySnapshot newSnapshot(String turnId, String projectSpaceId) {
        return new ChatTurnMemorySnapshot(
                turnId,
                "session-1",
                "personal-space-1",
                "experience-space-1",
                null,
                projectSpaceId,
                List.of("personal-space-1", "experience-space-1"),
                List.of(),
                true,
                false,
                true,
                Map.of("source", "test"),
                Instant.parse("2026-04-23T10:00:00Z")
        );
    }
}
