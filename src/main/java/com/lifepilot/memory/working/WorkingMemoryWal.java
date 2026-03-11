package com.lifepilot.memory.working;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
 * 工作记忆 WAL（Write-Ahead Log）持久化服务。
 *
 * <p>在 {@link WorkingMemory#append} 时同步写入一条轻量级 WAL 记录到 SQLite，
 * 在 {@link WorkingMemory#flush} 成功后按 session_id 批量删除。
 * 应用启动时检查 WAL 表残留记录，恢复未 flush 的会话数据。</p>
 *
 * @author zsg
 * @since 2026-03-11
 */
public class WorkingMemoryWal {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryWal.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkingMemoryWal(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        // 启用多态类型信息，以便反序列化时区分 ConversationSlot / ToolResultSlot / ReasoningSlot
        this.objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
    }

    /**
     * 追加一条 WAL 记录（与内存 append 同步调用）。
     *
     * @param sessionId 会话 ID
     * @param slot      槽位对象
     */
    public void append(String sessionId, WorkingMemorySlot slot) {
        try {
            String slotType = switch (slot) {
                case ConversationSlot _ -> "CONVERSATION";
                case ToolResultSlot _ -> "TOOL_RESULT";
                case ReasoningSlot _ -> "REASONING";
            };
            String slotJson = objectMapper.writeValueAsString(slot);
            jdbcTemplate.update(
                    "INSERT INTO working_memory_wal (session_id, slot_type, slot_json) VALUES (?, ?, ?)",
                    sessionId, slotType, slotJson);
        } catch (Exception e) {
            // WAL 写入失败不阻塞主流程，仅记录警告
            log.warn("WAL 写入失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 删除指定会话的所有 WAL 记录（flush 成功后调用）。
     *
     * @param sessionId 会话 ID
     */
    public void clearSession(String sessionId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM working_memory_wal WHERE session_id = ?", sessionId);
            if (deleted > 0) {
                log.debug("WAL 清理: sessionId={}, 删除记录数={}", sessionId, deleted);
            }
        } catch (Exception e) {
            log.warn("WAL 清理失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 加载所有残留的 WAL 记录，按 session_id 分组返回。
     *
     * <p>启动时调用，用于恢复上次异常退出未 flush 的会话数据。</p>
     *
     * @return session_id → 槽位列表的映射，无残留时返回空 Map
     */
    public Map<String, List<WorkingMemorySlot>> loadPendingSessions() {
        Map<String, List<WorkingMemorySlot>> result = new LinkedHashMap<>();
        try {
            var rows = jdbcTemplate.queryForList(
                    "SELECT session_id, slot_type, slot_json FROM working_memory_wal ORDER BY id ASC");
            for (var row : rows) {
                String sessionId = (String) row.get("session_id");
                String slotJson = (String) row.get("slot_json");
                try {
                    WorkingMemorySlot slot = objectMapper.readValue(slotJson, WorkingMemorySlot.class);
                    result.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(slot);
                } catch (Exception e) {
                    log.warn("WAL 记录反序列化失败: sessionId={}, error={}", sessionId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("WAL 加载失败: error={}", e.getMessage());
        }
        return result;
    }

    /**
     * 清空所有 WAL 记录（恢复完成后调用）。
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
