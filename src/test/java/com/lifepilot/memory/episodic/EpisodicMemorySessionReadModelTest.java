package com.lifepilot.memory.episodic;

import com.lifepilot.memory.config.MemoryProperties;
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
                CREATE TABLE chat_sessions (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    summary TEXT,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    is_pinned INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_message_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE chat_messages (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE chat_messages_fts USING fts5(
                    content,
                    content='chat_messages',
                    content_rowid='rowid',
                    tokenize='unicode61 remove_diacritics 2'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_chat_messages_fts_ai
                AFTER INSERT ON chat_messages
                BEGIN
                    INSERT INTO chat_messages_fts(rowid, content)
                    VALUES (new.rowid, new.content);
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_chat_messages_fts_ad
                AFTER DELETE ON chat_messages
                BEGIN
                    INSERT INTO chat_messages_fts(chat_messages_fts, rowid, content)
                    VALUES ('delete', old.rowid, old.content);
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_chat_messages_fts_au
                AFTER UPDATE ON chat_messages
                BEGIN
                    INSERT INTO chat_messages_fts(chat_messages_fts, rowid, content)
                    VALUES ('delete', old.rowid, old.content);
                    INSERT INTO chat_messages_fts(rowid, content)
                    VALUES (new.rowid, new.content);
                END
                """);

        episodicMemory = new EpisodicMemory(jdbcTemplate, new MemoryProperties());
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
    void sessionReadModelUsesChatSessionsAndChatMessagesAsSourceOfTruth() {
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

    private void insertSession(String id, String title, String summary, Instant createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO chat_sessions (
                            id, title, summary, message_count, is_pinned, archived,
                            last_message_at, created_at, updated_at
                        ) VALUES (?, ?, ?, 0, 0, 0, ?, ?, ?)
                        """,
                id, title, summary, createdAt.toString(), createdAt.toString(), createdAt.toString());
    }

    private void insertTurn(String sessionId, String messageId, String role, String content, Instant createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO chat_messages (id, session_id, role, content, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                messageId, sessionId, role, content, createdAt.toString());
        jdbcTemplate.update("""
                        UPDATE chat_sessions
                        SET message_count = message_count + 1,
                            last_message_at = ?,
                            updated_at = ?
                        WHERE id = ?
                        """,
                createdAt.toString(), createdAt.toString(), sessionId);
    }
}
