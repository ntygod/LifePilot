package com.lifepilot.memory.store.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话轮次记忆快照仓储。
 *
 * @author zsg
 * @since 2026-03-27
 */
@Repository
public class ChatTurnMemorySnapshotRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatTurnMemorySnapshotRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(ChatTurnMemorySnapshot snapshot) {
        jdbcTemplate.update("""
                INSERT INTO chat_turn_memory_snapshots (
                    turn_id, session_id, personal_space_id, experience_space_id, domain_write_space_id,
                    project_space_id,
                    read_space_ids_json, effective_knowledge_base_ids_json,
                    personal_learning_enabled, domain_learning_enabled, experience_learning_enabled,
                    resolution_source_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(turn_id) DO UPDATE SET
                    session_id = excluded.session_id,
                    personal_space_id = excluded.personal_space_id,
                    experience_space_id = excluded.experience_space_id,
                    domain_write_space_id = excluded.domain_write_space_id,
                    project_space_id = excluded.project_space_id,
                    read_space_ids_json = excluded.read_space_ids_json,
                    effective_knowledge_base_ids_json = excluded.effective_knowledge_base_ids_json,
                    personal_learning_enabled = excluded.personal_learning_enabled,
                    domain_learning_enabled = excluded.domain_learning_enabled,
                    experience_learning_enabled = excluded.experience_learning_enabled,
                    resolution_source_json = excluded.resolution_source_json
                """,
                snapshot.turnId(),
                snapshot.sessionId(),
                snapshot.personalSpaceId(),
                snapshot.experienceSpaceId(),
                snapshot.domainWriteSpaceId(),
                snapshot.projectSpaceId(),
                writeJson(snapshot.readSpaceIds()),
                writeJson(snapshot.effectiveKnowledgeBaseIds()),
                snapshot.personalLearningEnabled() ? 1 : 0,
                snapshot.domainLearningEnabled() ? 1 : 0,
                snapshot.experienceLearningEnabled() ? 1 : 0,
                writeJson(snapshot.resolutionSource()),
                snapshot.createdAt().toString()
        );
    }

    public Optional<ChatTurnMemorySnapshot> findByTurnId(String turnId) {
        List<ChatTurnMemorySnapshot> rows = jdbcTemplate.query("""
                SELECT turn_id, session_id, personal_space_id, experience_space_id, domain_write_space_id,
                       project_space_id,
                       read_space_ids_json, effective_knowledge_base_ids_json,
                       personal_learning_enabled, domain_learning_enabled, experience_learning_enabled,
                       resolution_source_json, created_at
                FROM chat_turn_memory_snapshots
                WHERE turn_id = ?
                """, this::mapRow, turnId);
        return rows.stream().findFirst();
    }

    private ChatTurnMemorySnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        String turnId = rs.getString("turn_id");
        return new ChatTurnMemorySnapshot(
                turnId,
                rs.getString("session_id"),
                rs.getString("personal_space_id"),
                rs.getString("experience_space_id"),
                rs.getString("domain_write_space_id"),
                rs.getString("project_space_id"),
                readStringList("read_space_ids_json", turnId, rs.getString("read_space_ids_json")),
                readStringList("effective_knowledge_base_ids_json", turnId,
                        rs.getString("effective_knowledge_base_ids_json")),
                rs.getInt("personal_learning_enabled") == 1,
                rs.getInt("domain_learning_enabled") == 1,
                rs.getInt("experience_learning_enabled") == 1,
                readJsonMap("resolution_source_json", turnId, rs.getString("resolution_source_json")),
                Instant.parse(rs.getString("created_at"))
        );
    }

    private List<String> readStringList(String columnName, String turnId, @Nullable String json) {
        requireJson(columnName, turnId, json);
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "解析会话轮次记忆快照列表失败: column=" + columnName + ", turnId=" + turnId, e);
        }
    }

    private Map<String, Object> readJsonMap(String columnName, String turnId, @Nullable String json) {
        requireJson(columnName, turnId, json);
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "解析会话轮次记忆快照来源信息失败: column=" + columnName + ", turnId=" + turnId, e);
        }
    }

    private void requireJson(String columnName, String turnId, @Nullable String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "会话轮次记忆快照字段不能为空: column=" + columnName + ", turnId=" + turnId);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化会话轮次记忆快照失败", e);
        }
    }
}
