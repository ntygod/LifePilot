package com.lifepilot.memory.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FtsSearcher transcript 集成测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
class FtsSearcher_集成测试 {

    private JdbcTemplate jdbcTemplate;
    private FtsSearcher ftsSearcher;

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);

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
                    created_at TEXT NOT NULL
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
                CREATE TABLE temporal_entities (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT,
                    properties_json TEXT,
                    version INTEGER NOT NULL DEFAULT 1,
                    is_current INTEGER NOT NULL DEFAULT 1,
                    valid_from TEXT NOT NULL,
                    valid_to TEXT,
                    source_conversation_id TEXT,
                    extraction_confidence REAL NOT NULL DEFAULT 1.0,
                    importance_score REAL NOT NULL DEFAULT 0.5,
                    access_count INTEGER NOT NULL DEFAULT 0,
                    last_accessed_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    lifecycle_state TEXT NOT NULL DEFAULT 'ACTIVE'
                )
                """);

        ftsSearcher = new FtsSearcher(jdbcTemplate);
    }

    @Test
    void search_query为空时直接拒绝() {
        assertThatThrownBy(() -> ftsSearcher.search("   ", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全文搜索 query 不能为空");
    }

    @Test
    void search_topK非法时直接拒绝() {
        assertThatThrownBy(() -> ftsSearcher.search("oolong", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全文搜索 topK 必须大于 0");
    }

    @Test
    void search_使用TranscriptFts关联当前实体() {
        Instant now = Instant.parse("2026-03-23T12:00:00Z");
        jdbcTemplate.update("""
                        INSERT INTO session_transcript_entries (
                            id, session_id, branch_id, entry_type, role, visible_to_model,
                            visible_to_user, payload_json, token_estimate, created_at
                        ) VALUES (?, ?, 'main', 'user_message', ?, 1, 1, ?, 0, ?)
                        """,
                "msg-1", "session-1", "user",
                "{\"content\":\"I prefer oolong tea and want to remember this preference.\"}",
                now.toString());
        jdbcTemplate.update("""
                        INSERT INTO temporal_entities (
                            id, type, name, description, version, is_current, valid_from,
                            source_conversation_id, extraction_confidence, importance_score,
                            access_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 1, 1, ?, ?, 0.9, 0.8, 0, ?, ?)
                        """,
                "entity-1",
                "PREFERENCE",
                "oolong preference",
                "user prefers oolong tea",
                now.toString(),
                "session-1",
                now.toString(),
                now.toString());

        var results = ftsSearcher.search("oolong", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().entityId()).isEqualTo("entity-1");
        assertThat(results.getFirst().name()).isEqualTo("oolong preference");
    }

    @Test
    void search_长自然语言查询包含编号时应命中实体文本() {
        Instant now = Instant.parse("2026-05-07T06:10:00Z");
        jdbcTemplate.update("""
                        INSERT INTO temporal_entities (
                            id, type, name, description, version, is_current, valid_from,
                            source_conversation_id, extraction_confidence, importance_score,
                            access_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 1, 1, ?, ?, 0.9, 0.2, 0, ?, ?)
                        """,
                "entity-cancel",
                "PREFERENCE",
                "MT-CANCEL-0507 不提醒下午5点检查记忆抽取日志",
                "用户明确要求取消 MT-CANCEL-0507，并且以后不要再提醒下午5点检查记忆抽取日志。",
                now.toString(),
                "session-cancel",
                now.toString(),
                now.toString());
        jdbcTemplate.update("""
                        INSERT INTO temporal_entities (
                            id, type, name, description, version, is_current, valid_from,
                            source_conversation_id, extraction_confidence, importance_score,
                            access_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 1, 1, ?, ?, 0.9, 1.0, 0, ?, ?)
                        """,
                "entity-other",
                "CUSTOM",
                "高重要度无关实体",
                "这条实体不包含目标编号。",
                now.toString(),
                "session-other",
                now.toString(),
                now.toString());

        var results = ftsSearcher.search("取消 MT-CANCEL-0507，以后不要再提醒我下午5点检查记忆抽取日志", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().entityId()).isEqualTo("entity-cancel");
        assertThat(results.getFirst().score()).isGreaterThanOrEqualTo(4.0f);
    }

    @Test
    void search_实体重要度越界时直接暴露错误() {
        Instant now = Instant.parse("2026-05-07T06:10:00Z");
        jdbcTemplate.update("""
                        INSERT INTO temporal_entities (
                            id, type, name, description, version, is_current, valid_from,
                            source_conversation_id, extraction_confidence, importance_score,
                            access_count, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 1, 1, ?, ?, 0.9, 1.5, 0, ?, ?)
                        """,
                "entity-bad-score",
                "PREFERENCE",
                "异常重要度实体",
                "这条实体的重要度超过允许范围。",
                now.toString(),
                "session-bad-score",
                now.toString(),
                now.toString());

        assertThatThrownBy(() -> ftsSearcher.search("异常重要度实体", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("排名条目重要度必须在 [0,1] 范围内");
    }

    @Test
    void search_缺失TranscriptFts表时应直接暴露错误() {
        jdbcTemplate.execute("DROP TABLE session_transcript_entries_fts");

        assertThatThrownBy(() -> ftsSearcher.search("oolong", 5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("session_transcript_entries_fts");
    }
}
