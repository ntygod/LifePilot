package com.lifepilot.memory.episodic;

import com.lifepilot.memory.store.episodic.EpisodicMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodicMemorySessionReadModelTest {

    private JdbcTemplate jdbcTemplate;
    private EpisodicMemory episodicMemory;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE session_store (
                    session_id TEXT PRIMARY KEY,
                    channel TEXT NOT NULL DEFAULT 'web',
                    chat_type TEXT NOT NULL DEFAULT 'chat',
                    title TEXT,
                    summary TEXT,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_message_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_activity_at TEXT NOT NULL,
                    provider_override TEXT,
                    model_override TEXT,
                    thinking_level TEXT,
                    reasoning_level TEXT,
                    config_json TEXT NOT NULL DEFAULT '{}',
                    context_tokens_estimate INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0,
                    output_tokens INTEGER NOT NULL DEFAULT 0,
                    total_tokens INTEGER NOT NULL DEFAULT 0,
                    compaction_count INTEGER NOT NULL DEFAULT 0,
                    memory_flush_at TEXT,
                    active_branch_id TEXT NOT NULL DEFAULT 'main',
                    project_id TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE session_transcript_entries (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    parent_id TEXT,
                    branch_id TEXT NOT NULL DEFAULT 'main',
                    entry_type TEXT NOT NULL,
                    role TEXT,
                    turn_id TEXT,
                    trace_id TEXT,
                    visible_to_model INTEGER NOT NULL DEFAULT 1,
                    visible_to_user INTEGER NOT NULL DEFAULT 1,
                    payload_json TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE session_transcript_compressions (
                    entry_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    compression_level INTEGER NOT NULL DEFAULT 0,
                    compressed_content TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (entry_id) REFERENCES session_transcript_entries(id) ON DELETE CASCADE,
                    FOREIGN KEY (session_id) REFERENCES session_store(session_id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE session_transcript_entries_fts USING fts5(
                    entry_id UNINDEXED,
                    session_id UNINDEXED,
                    role UNINDEXED,
                    content,
                    tokenize='unicode61 remove_diacritics 2'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_session_transcript_entries_fts_ai
                AFTER INSERT ON session_transcript_entries
                BEGIN
                    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
                    SELECT new.rowid,
                           new.id,
                           new.session_id,
                           COALESCE(new.role, ''),
                           json_extract(new.payload_json, '$.content')
                    WHERE new.entry_type IN ('user_message', 'assistant_message')
                      AND new.visible_to_user = 1
                      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_session_transcript_entries_fts_ad
                AFTER DELETE ON session_transcript_entries
                BEGIN
                    DELETE FROM session_transcript_entries_fts
                    WHERE rowid = old.rowid
                      AND old.entry_type IN ('user_message', 'assistant_message')
                      AND old.visible_to_user = 1
                      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_session_transcript_entries_fts_au
                AFTER UPDATE ON session_transcript_entries
                BEGIN
                    DELETE FROM session_transcript_entries_fts
                    WHERE rowid = old.rowid
                      AND old.entry_type IN ('user_message', 'assistant_message')
                      AND old.visible_to_user = 1
                      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';

                    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
                    SELECT new.rowid,
                           new.id,
                           new.session_id,
                           COALESCE(new.role, ''),
                           json_extract(new.payload_json, '$.content')
                    WHERE new.entry_type IN ('user_message', 'assistant_message')
                      AND new.visible_to_user = 1
                      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
                END
                """);

        episodicMemory = new EpisodicMemory(jdbcTemplate);
    }

    @Test
    void searchSnippetsExcludingSessionBuildsSurroundingTurnSnippet() {
        insertSession("s1", "current", "current summary", Instant.parse("2026-03-20T10:00:00Z"));
        insertTurn("s1", "m1", "user", "this is the current session", Instant.parse("2026-03-20T10:00:01Z"));
        insertTurn("s1", "m2", "assistant", "ack", Instant.parse("2026-03-20T10:00:02Z"));

        insertSession("s2", "tea preferences", "tea notes", Instant.parse("2026-03-19T09:00:00Z"));
        insertTurn("s2", "m3", "user", "I started exploring tea recently.", Instant.parse("2026-03-19T09:00:01Z"));
        insertTurn("s2", "m4", "assistant", "Nice, tea has a lot of variety.", Instant.parse("2026-03-19T09:00:02Z"));
        insertTurn("s2", "m5", "user", "My favorite is oolong tea.", Instant.parse("2026-03-19T09:00:03Z"));
        insertTurn("s2", "m6", "assistant", "I will remember that you like oolong tea.", Instant.parse("2026-03-19T09:00:04Z"));
        insertTurn("s2", "m7", "user", "Remind me to restock next week.", Instant.parse("2026-03-19T09:00:05Z"));
        insertTurn("s2", "m8", "assistant", "Okay, restock reminder noted.", Instant.parse("2026-03-19T09:00:06Z"));

        var snippets = episodicMemory.searchSnippetsExcludingSession("oolong", "s1", 3);

        assertThat(snippets).hasSize(1);
        var snippet = snippets.getFirst();
        assertThat(snippet.sessionId()).isEqualTo("s2");
        assertThat(snippet.sessionTitle()).isEqualTo("tea preferences");
        assertThat(snippet.messages()).extracting(MessageRecord::content)
                .contains(
                        "I started exploring tea recently.",
                        "My favorite is oolong tea.",
                        "Remind me to restock next week.");
    }

    @Test
    void searchSnippetsExcludingSession_长查询包含编号时应通过精确兜底召回() {
        insertSession("s1", "current", "current summary", Instant.parse("2026-05-07T06:15:00Z"));
        insertTurn("s1", "m1", "user", "请回忆一下我之前提到过的 MT-RECALL-0507，验证码是什么？",
                Instant.parse("2026-05-07T06:15:01Z"));

        insertSession("s2", "recall marker", "验证码记录", Instant.parse("2026-05-07T05:00:00Z"));
        insertTurn("s2", "m2", "user", "MT-RECALL-0507 的验证码是 RQ-7391，请稍后验证召回。",
                Instant.parse("2026-05-07T05:00:01Z"));
        insertTurn("s2", "m3", "assistant", "已记录这条测试验证码。",
                Instant.parse("2026-05-07T05:00:02Z"));

        var snippets = episodicMemory.searchSnippetsExcludingSession(
                "请回忆一下我之前提到过的 MT-RECALL-0507，验证码是什么？", "s1", 3);

        assertThat(snippets).hasSize(1);
        assertThat(snippets.getFirst().sessionId()).isEqualTo("s2");
        assertThat(snippets.getFirst().messages()).extracting(MessageRecord::content)
                .anySatisfy(content -> assertThat(content).contains("RQ-7391"));
    }

    @Test
    void searchSnippetsExcludingSession_泛化跨会话问题应返回最近会话片段() {
        insertSession("s1", "current", "current summary", Instant.parse("2026-05-07T06:20:00Z"));
        insertTurn("s1", "m1", "user", "你还记得我在别的会话里和你聊过什么吗",
                Instant.parse("2026-05-07T06:20:01Z"));

        insertSession("s2", "memory review", "跨会话召回复盘", Instant.parse("2026-05-07T06:10:00Z"));
        insertTurn("s2", "m2", "user", "我们讨论了 transcript FTS 没有同步导致 recall 失效。",
                Instant.parse("2026-05-07T06:10:01Z"));
        insertTurn("s2", "m3", "assistant", "结论是需要补齐 FTS 触发器和泛化查询兜底。",
                Instant.parse("2026-05-07T06:10:02Z"));

        var snippets = episodicMemory.searchSnippetsExcludingSession(
                "你还记得我在别的会话里和你聊过什么吗", "s1", 3);

        assertThat(snippets).hasSize(1);
        assertThat(snippets.getFirst().sessionId()).isEqualTo("s2");
        assertThat(snippets.getFirst().messages()).extracting(MessageRecord::content)
                .contains("我们讨论了 transcript FTS 没有同步导致 recall 失效。");
    }

    @Test
    void sessionReadModelUsesSessionStoreAndTranscriptAsSourceOfTruth() {
        insertSession("s1", "tea preferences", "tea notes", Instant.parse("2026-03-19T09:00:00Z"));
        insertTurn("s1", "m1", "user", "My favorite is oolong tea.", Instant.parse("2026-03-19T09:00:01Z"));
        insertTurn("s1", "m2", "assistant", "I will remember that.", Instant.parse("2026-03-19T09:00:02Z"));

        insertSession("s2", "travel ideas", "kyoto plan", Instant.parse("2026-03-20T09:00:00Z"));
        insertTurn("s2", "m3", "user", "Let's plan Kyoto.", Instant.parse("2026-03-20T09:00:01Z"));
        insertTurn("s2", "m4", "assistant", "Sure, we can start with hotels.", Instant.parse("2026-03-20T09:00:02Z"));

        assertThat(episodicMemory.countConversations()).isEqualTo(2);
        assertThat(episodicMemory.search("oolong")).extracting(ConversationRecord::id).containsExactly("s1");

        var conversation = episodicMemory.getById("s1").orElseThrow();
        assertThat(conversation.goal()).isEqualTo("tea preferences");
        assertThat(conversation.messages()).hasSize(2);

        var listed = episodicMemory.listConversations(0, 10);
        assertThat(listed).extracting(ConversationRecord::id).contains("s1", "s2");
    }

    @Test
    void compress_会写入Transcript压缩投影并影响读取结果() {
        insertSession("s1", "tea preferences", "tea notes", Instant.parse("2026-03-19T09:00:00Z"));
        insertTurn("s1", "m1", "user", "My favorite is oolong tea.", Instant.parse("2026-03-19T09:00:01Z"));
        insertTurn("s1", "m2", "assistant", "I will remember that.", Instant.parse("2026-03-19T09:00:02Z"));

        episodicMemory.compress("s1", CompressionLevel.SUMMARY, java.util.Map.of(
                "m1", "用户偏好：乌龙茶",
                "m2", "助手确认已记住偏好"
        ));

        var conversation = episodicMemory.getById("s1").orElseThrow();

        assertThat(conversation.messages()).extracting(MessageRecord::effectiveContent)
                .containsExactly("用户偏好：乌龙茶", "助手确认已记住偏好");
        assertThat(conversation.messages()).extracting(MessageRecord::compressionLevel)
                .containsOnly(CompressionLevel.SUMMARY);
    }

    private void insertSession(String id, String title, String summary, Instant createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO session_store (
                            session_id, title, summary, message_count, is_pinned, archived,
                            last_message_at, created_at, updated_at, last_activity_at
                        ) VALUES (?, ?, ?, 0, 0, 0, ?, ?, ?, ?)
                        """,
                id, title, summary,
                createdAt.toString(), createdAt.toString(), createdAt.toString(), createdAt.toString());
    }

    private void insertTurn(String sessionId, String messageId, String role, String content, Instant createdAt) {
        String payloadJson = "{\"content\":\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        jdbcTemplate.update("""
                        INSERT INTO session_transcript_entries (
                            id, session_id, branch_id, entry_type, role, visible_to_model,
                            visible_to_user, payload_json, token_estimate, created_at
                        ) VALUES (?, ?, 'main', ?, ?, 1, 1, ?, 0, ?)
                        """,
                messageId, sessionId, "user".equals(role) ? "user_message" : "assistant_message",
                role, payloadJson, createdAt.toString());
        jdbcTemplate.update("""
                        UPDATE session_store
                        SET message_count = message_count + 1,
                            last_message_at = ?,
                            updated_at = ?,
                            last_activity_at = ?
                        WHERE session_id = ?
                        """,
                createdAt.toString(), createdAt.toString(), createdAt.toString(), sessionId);
    }
}
