package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionStoreRepository 项目归属集成测试 —— 使用 SQLite 内存库 + 手动建表验证 project_id 读写。
 *
 * <p>Plan 1 对话链路校正：project_id 从死表 conversations 迁移到真实对话表 session_store（V17）。
 * 本测试验证 Repository 的 save / findBySessionId / ensureSessionShell 在 project_id 语义上的正确性。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class SessionStoreRepository_项目归属测试 {
    private SessionStoreRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 对照 V1__init_schema.sql 的 session_store 结构，加上 V17 新增的 project_id 列
        jdbc.execute("""
                CREATE TABLE session_store (
                    session_id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL DEFAULT 'web',
                    chat_type TEXT NOT NULL DEFAULT 'chat',
                    title TEXT NOT NULL,
                    summary TEXT,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_message_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_activity_at TEXT NOT NULL,
                    config_json TEXT NOT NULL DEFAULT '{}',
                    context_tokens_estimate INTEGER NOT NULL DEFAULT 0,
                    compaction_count INTEGER NOT NULL DEFAULT 0,
                    memory_flush_at TEXT,
                    active_branch_id TEXT NOT NULL DEFAULT 'main',
                    project_id TEXT
                )
                """);
        repository = new SessionStoreRepository(jdbc, new ObjectMapper(), null);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void save_带projectId_能回读() {
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        String projectId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(
                sessionId, "论文项目对话", null, 0, false, false,
                null, now, now, projectId
        );

        repository.save(session);

        Optional<SessionStoreRepository.SessionStoreRow> row = repository.findBySessionId(sessionId);
        assertThat(row).isPresent();
        assertThat(row.get().projectId()).isEqualTo(projectId);
    }

    @Test
    void save_projectId为null_能回读为null() {
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(
                sessionId, "主账户对话", null, 0, false, false,
                null, now, now, null
        );

        repository.save(session);

        Optional<SessionStoreRepository.SessionStoreRow> row = repository.findBySessionId(sessionId);
        assertThat(row).isPresent();
        assertThat(row.get().projectId()).isNull();
    }

    @Test
    void ensureSessionShell_默认projectId为null() {
        // 外部渠道（如飞书）首次消息时 ensureSessionShell 建壳，此时没有项目上下文
        String sessionId = "feishu:chat123:openid456";

        repository.ensureSessionShell(sessionId);

        Optional<SessionStoreRepository.SessionStoreRow> row = repository.findBySessionId(sessionId);
        assertThat(row).isPresent();
        assertThat(row.get().projectId()).isNull();
    }

    @Test
    void findIdsByProjectId_仅返回指定项目的会话id() {
        Instant now = Instant.now();
        String projectA = UUID.randomUUID().toString();
        String projectB = UUID.randomUUID().toString();
        String sessionA1 = UUID.randomUUID().toString();
        String sessionA2 = UUID.randomUUID().toString();
        String sessionB = UUID.randomUUID().toString();
        String sessionMain = UUID.randomUUID().toString();

        // 项目 A 两个会话 + 项目 B 一个 + 主账户一个
        repository.save(new ChatSession(sessionA1, "A1", null, 0, false, false, null, now, now, projectA));
        repository.save(new ChatSession(sessionA2, "A2", null, 0, false, false, null, now, now, projectA));
        repository.save(new ChatSession(sessionB, "B", null, 0, false, false, null, now, now, projectB));
        repository.save(new ChatSession(sessionMain, "主账户", null, 0, false, false, null, now, now, null));

        List<String> idsOfA = repository.findIdsByProjectId(projectA);
        assertThat(idsOfA).containsExactlyInAnyOrder(sessionA1, sessionA2);

        List<String> idsOfB = repository.findIdsByProjectId(projectB);
        assertThat(idsOfB).containsExactly(sessionB);
    }

    @Test
    void findIdsByProjectId_无归属返回空列表() {
        Instant now = Instant.now();
        // 只有主账户会话
        repository.save(new ChatSession(
                UUID.randomUUID().toString(), "主账户", null, 0, false, false,
                null, now, now, null
        ));

        assertThat(repository.findIdsByProjectId(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void findIdsByProjectId_空或null项目id返回空列表() {
        assertThat(repository.findIdsByProjectId(null)).isEmpty();
        assertThat(repository.findIdsByProjectId("")).isEmpty();
        assertThat(repository.findIdsByProjectId("   ")).isEmpty();
    }

    @Test
    void save_update路径_可改projectId() {
        // 验证 ON CONFLICT DO UPDATE 也能更新 project_id（从 null → 有值 / 有值 → null）
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();

        // 先插入 project_id = null
        repository.save(new ChatSession(
                sessionId, "初始标题", null, 0, false, false,
                null, now, now, null
        ));
        assertThat(repository.findBySessionId(sessionId).orElseThrow().projectId()).isNull();

        // 再用同一 sessionId 更新为具体项目
        String projectId = UUID.randomUUID().toString();
        repository.save(new ChatSession(
                sessionId, "新标题", null, 0, false, false,
                null, now, now, projectId
        ));
        assertThat(repository.findBySessionId(sessionId).orElseThrow().projectId()).isEqualTo(projectId);

        // 再改回 null（项目解绑）
        repository.save(new ChatSession(
                sessionId, "回到主账户", null, 0, false, false,
                null, now, now, null
        ));
        assertThat(repository.findBySessionId(sessionId).orElseThrow().projectId()).isNull();
    }
}
