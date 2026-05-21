package com.lifepilot.memory.episodic;

import com.lifepilot.memory.store.episodic.EpisodicMemory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EpisodicMemory 真实 V1 schema 召回测试。
 *
 * @author zsg
 * @since 2026-05-07
 */
class EpisodicMemory_V1Schema召回测试 {

    @TempDir
    Path tempDir;

    @Test
    void 真实V1_schema_应同步FTS并支持跨会话回忆() {
        String dbPath = tempDir.resolve("episodic-v1.db").toString().replace("\\", "/");
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dbPath, true);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            jdbcTemplate.update("""
                    INSERT INTO session_store (
                        session_id, channel, chat_type, title, summary, message_count,
                        is_pinned, archived, last_message_at, created_at, updated_at,
                        last_activity_at, active_branch_id, project_id
                    ) VALUES (?, 'web', 'chat', ?, ?, 0, 0, 0, ?, ?, ?, ?, 'main', NULL)
                    """,
                    "current", "当前会话", "current summary",
                    Instant.parse("2026-05-07T06:30:00Z").toString(),
                    Instant.parse("2026-05-07T06:30:00Z").toString(),
                    Instant.parse("2026-05-07T06:30:00Z").toString(),
                    Instant.parse("2026-05-07T06:30:00Z").toString());
            jdbcTemplate.update("""
                    INSERT INTO session_store (
                        session_id, channel, chat_type, title, summary, message_count,
                        is_pinned, archived, last_message_at, created_at, updated_at,
                        last_activity_at, active_branch_id, project_id
                    ) VALUES (?, 'web', 'chat', ?, ?, 0, 0, 0, ?, ?, ?, ?, 'main', NULL)
                    """,
                    "history", "历史会话", "recall summary",
                    Instant.parse("2026-05-07T06:20:00Z").toString(),
                    Instant.parse("2026-05-07T06:20:00Z").toString(),
                    Instant.parse("2026-05-07T06:20:00Z").toString(),
                    Instant.parse("2026-05-07T06:20:00Z").toString());

            insertTranscript(jdbcTemplate, "current", "c1", "user", "当前会话只是占位", Instant.parse("2026-05-07T06:30:01Z"));
            insertTranscript(jdbcTemplate, "history", "h1", "user", "我们上次聊了 transcript FTS 没有同步的问题", Instant.parse("2026-05-07T06:20:01Z"));
            insertTranscript(jdbcTemplate, "history", "h2", "assistant", "解决思路是补齐 FTS 触发器", Instant.parse("2026-05-07T06:20:02Z"));

            Integer ftsCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM session_transcript_entries_fts WHERE session_id = ?",
                    Integer.class,
                    "history");
            assertThat(ftsCount).isEqualTo(2);

            var episodicMemory = new EpisodicMemory(jdbcTemplate);
            var snippets = episodicMemory.searchSnippetsExcludingSession("transcript", "current", 3);

            assertThat(snippets).hasSize(1);
            assertThat(snippets.getFirst().sessionId()).isEqualTo("history");
            assertThat(snippets.getFirst().messages()).extracting(MessageRecord::content)
                    .contains("我们上次聊了 transcript FTS 没有同步的问题");
        } finally {
            dataSource.destroy();
        }
    }

    private void insertTranscript(JdbcTemplate jdbcTemplate,
                                  String sessionId,
                                  String entryId,
                                  String role,
                                  String content,
                                  Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO session_transcript_entries (
                    id, session_id, parent_id, branch_id, entry_type, role,
                    turn_id, trace_id, visible_to_model, visible_to_user,
                    payload_json, token_estimate, created_at
                ) VALUES (?, ?, NULL, 'main', ?, ?, NULL, NULL, 1, 1, ?, 0, ?)
                """,
                entryId,
                sessionId,
                "user".equals(role) ? "user_message" : "assistant_message",
                role,
                "{\"content\":\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
                createdAt.toString());
    }
}
