package com.lifepilot.memory.working;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Working-memory WAL persistence.
 *
 * <p>The WAL keeps lightweight slot records in SQLite so unfinished L1 sessions
 * can be recovered on next startup.
 */
public class WorkingMemoryWal {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryWal.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkingMemoryWal(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Append one WAL record.
     */
    public void append(String sessionId, WorkingMemorySlot slot) {
        try {
            String slotType = switch (slot) {
                case ConversationSlot _ -> "CONVERSATION";
                case ToolResultSlot _ -> "TOOL_RESULT";
                case ReasoningSlot _ -> "REASONING";
            };
            String slotJson = objectMapper.writerFor(WorkingMemorySlot.class).writeValueAsString(slot);
            jdbcTemplate.update(
                    "INSERT INTO working_memory_wal (session_id, slot_type, slot_json) VALUES (?, ?, ?)",
                    sessionId, slotType, slotJson);
        } catch (Exception e) {
            log.warn("WAL 写入失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * Delete all WAL rows for one session after a successful flush.
     */
    public void clearSession(String sessionId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM working_memory_wal WHERE session_id = ?",
                    sessionId);
            if (deleted > 0) {
                log.debug("WAL 清理: sessionId={}, 删除记录数={}", sessionId, deleted);
            }
        } catch (Exception e) {
            log.warn("WAL 清理失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * Load all pending WAL rows grouped by session.
     *
     * <p>Rows that cannot be deserialized are treated as invalid startup residue
     * and deleted immediately instead of attempting legacy-format recovery.</p>
     */
    public Map<String, List<WorkingMemorySlot>> loadPendingSessions() {
        Map<String, List<WorkingMemorySlot>> result = new LinkedHashMap<>();
        try {
            List<Long> invalidWalIds = new ArrayList<>();
            var rows = jdbcTemplate.queryForList(
                    "SELECT id, session_id, slot_json FROM working_memory_wal ORDER BY id ASC");
            for (var row : rows) {
                long walId = ((Number) row.get("id")).longValue();
                String sessionId = (String) row.get("session_id");
                String slotJson = (String) row.get("slot_json");
                try {
                    WorkingMemorySlot slot = objectMapper.readValue(slotJson, WorkingMemorySlot.class);
                    result.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(slot);
                } catch (Exception e) {
                    invalidWalIds.add(walId);
                }
            }
            if (!invalidWalIds.isEmpty()) {
                for (Long walId : invalidWalIds) {
                    jdbcTemplate.update("DELETE FROM working_memory_wal WHERE id = ?", walId);
                }
                log.warn("WAL 检测到 {} 条无效记录，已自动删除", invalidWalIds.size());
            }
        } catch (Exception e) {
            log.warn("WAL 加载失败: error={}", e.getMessage());
        }
        return result;
    }

    /**
     * Delete all WAL rows after recovery completes.
     */
    public void clearAll() {
        try {
            int deleted = jdbcTemplate.update("DELETE FROM working_memory_wal");
            if (deleted > 0) {
                log.info("WAL 全量清理: 删除记录数={}", deleted);
            }
        } catch (Exception e) {
            log.warn("WAL 全量清理失败: error={}", e.getMessage());
        }
    }
}
