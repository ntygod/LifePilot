package com.lifepilot.conversation.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionStoreRepository 按 project_id 过滤 Web 会话列表的集成测试。
 *
 * <p>Plan 1 Task 13：对话列表 API 支持按 projectId 过滤。</p>
 *
 * <p>验证三种 ProjectScope 语义：</p>
 * <ul>
 *   <li>{@link SessionStoreRepository.ProjectScope.MainAccount} — 只返回 project_id IS NULL 的主账户对话</li>
 *   <li>{@link SessionStoreRepository.ProjectScope.OfProject} — 只返回归属指定项目的对话</li>
 *   <li>{@link SessionStoreRepository.ProjectScope.All} — 不按项目维度过滤</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("SessionStoreRepository 按项目过滤列表")
class SessionStoreRepository_按项目过滤列表测试 {

    private SessionStoreRepository repository;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
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

    private String saveSession(String title, String projectId) {
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        repository.save(new ChatSession(
                sessionId, title, null, 0, false, false,
                null, now, now, projectId
        ));
        return sessionId;
    }

    @Test
    void 默认旧签名_等价于只返回主账户对话() {
        String mainId = saveSession("主账户对话", null);
        saveSession("项目 P1 对话", "p-1");
        saveSession("项目 P2 对话", "p-2");

        List<SessionStoreRepository.SessionStoreRow> rows =
                repository.findWebSessionsByConditions(null, null, null, null, null, null);

        assertThat(rows).extracting(SessionStoreRepository.SessionStoreRow::sessionId)
                .containsExactlyInAnyOrder(mainId);
    }

    @Test
    void 主账户作用域_仅返回projectId为null的对话() {
        String mainA = saveSession("主账户 A", null);
        String mainB = saveSession("主账户 B", null);
        saveSession("项目 P1 对话", "p-1");

        List<SessionStoreRepository.SessionStoreRow> rows = repository.findWebSessionsByConditions(
                null, null, null, null, null, null,
                SessionStoreRepository.ProjectScope.mainAccount()
        );

        assertThat(rows).extracting(SessionStoreRepository.SessionStoreRow::sessionId)
                .containsExactlyInAnyOrder(mainA, mainB);
        assertThat(rows).allSatisfy(r -> assertThat(r.projectId()).isNull());
    }

    @Test
    void 指定项目作用域_仅返回归属该项目的对话() {
        saveSession("主账户对话", null);
        String p1A = saveSession("项目 P1 对话 A", "p-1");
        String p1B = saveSession("项目 P1 对话 B", "p-1");
        saveSession("项目 P2 对话", "p-2");

        List<SessionStoreRepository.SessionStoreRow> rows = repository.findWebSessionsByConditions(
                null, null, null, null, null, null,
                SessionStoreRepository.ProjectScope.ofProject("p-1")
        );

        assertThat(rows).extracting(SessionStoreRepository.SessionStoreRow::sessionId)
                .containsExactlyInAnyOrder(p1A, p1B);
        assertThat(rows).allSatisfy(r -> assertThat(r.projectId()).isEqualTo("p-1"));
    }

    @Test
    void 指定项目作用域_无匹配时返回空列表() {
        saveSession("主账户对话", null);
        saveSession("项目 P1 对话", "p-1");

        List<SessionStoreRepository.SessionStoreRow> rows = repository.findWebSessionsByConditions(
                null, null, null, null, null, null,
                SessionStoreRepository.ProjectScope.ofProject("not-exist")
        );

        assertThat(rows).isEmpty();
    }

    @Test
    void 全量作用域_返回所有Web会话() {
        String mainId = saveSession("主账户对话", null);
        String p1Id = saveSession("项目 P1 对话", "p-1");
        String p2Id = saveSession("项目 P2 对话", "p-2");

        List<SessionStoreRepository.SessionStoreRow> rows = repository.findWebSessionsByConditions(
                null, null, null, null, null, null,
                SessionStoreRepository.ProjectScope.all()
        );

        assertThat(rows).extracting(SessionStoreRepository.SessionStoreRow::sessionId)
                .containsExactlyInAnyOrder(mainId, p1Id, p2Id);
    }

    @Test
    void 项目作用域与其他条件组合_生效() {
        saveSession("项目 P1 对话 A", "p-1");
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        repository.save(new ChatSession(
                sessionId, "项目 P1 置顶对话", null, 0, true, false,
                null, now, now, "p-1"
        ));
        saveSession("主账户置顶对话", null);

        // projectId = p-1 且 pinned = true —— 只保留项目 P1 置顶对话
        List<SessionStoreRepository.SessionStoreRow> rows = repository.findWebSessionsByConditions(
                null, true, null, null, null, null,
                SessionStoreRepository.ProjectScope.ofProject("p-1")
        );

        assertThat(rows).extracting(SessionStoreRepository.SessionStoreRow::sessionId)
                .containsExactly(sessionId);
    }

    @Test
    void OfProject_空串或null_构造应失败() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SessionStoreRepository.ProjectScope.OfProject(null)
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SessionStoreRepository.ProjectScope.OfProject("  ")
        );
    }
}
