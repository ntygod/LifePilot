package com.lifepilot.memory.working;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkingMemoryWalTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private WorkingMemoryWal wal;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE working_memory_wal (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    slot_type  TEXT NOT NULL,
                    slot_json  TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                )
                """);
        wal = new WorkingMemoryWal(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    void append_writesExplicitTypeAndLoadsBack() {
        Instant createdAt = Instant.parse("2026-03-12T12:00:00Z");
        var slot = new ConversationSlot("user", "hello", 7, 0.8f, false, null, createdAt);

        wal.append("session-1", slot);

        String slotJson = jdbcTemplate.queryForObject(
                "SELECT slot_json FROM working_memory_wal WHERE session_id = ?",
                String.class,
                "session-1");
        assertNotNull(slotJson);
        assertTrue(slotJson.contains("\"type\":\"CONVERSATION\""));

        var pendingSessions = wal.loadPendingSessions();
        List<WorkingMemorySlot> slots = pendingSessions.get("session-1");
        assertNotNull(slots);
        assertEquals(1, slots.size());

        ConversationSlot restored = assertInstanceOf(ConversationSlot.class, slots.getFirst());
        assertEquals("user", restored.role());
        assertEquals("hello", restored.content());
        assertEquals(createdAt, restored.createdAt());
    }

    @Test
    void loadPendingSessions_deletesInvalidLegacyRows() {
        jdbcTemplate.update(
                "INSERT INTO working_memory_wal (session_id, slot_type, slot_json) VALUES (?, ?, ?)",
                "invalid-session",
                "CONVERSATION",
                """
                {
                  "role": "assistant",
                  "content": "legacy payload",
                  "tokenCount": 9,
                  "importance": 0.6,
                  "isPinned": false,
                  "toolCallJson": null,
                  "createdAt": "2026-03-12T12:30:00Z"
                }
                """);

        var pendingSessions = wal.loadPendingSessions();

        assertTrue(pendingSessions.isEmpty());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM working_memory_wal WHERE session_id = ?",
                Integer.class,
                "invalid-session");
        assertEquals(0, count);
    }
}
