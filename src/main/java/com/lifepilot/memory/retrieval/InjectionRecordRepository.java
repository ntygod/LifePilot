package com.lifepilot.memory.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 注入记录数据访问层 — 记录每次 AI 回复时注入了哪些记忆实体。
 *
 * @author zsg
 * @since 2026-03-13
 */
public class InjectionRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(InjectionRecordRepository.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InjectionRecordRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存注入记录。
     *
     * @param messageId AI 回复消息 ID
     * @param sessionId 会话 ID
     * @param entityIds 注入的实体 ID 列表
     */
    public void save(String messageId, String sessionId, List<String> entityIds) {
        try {
            var entityIdsJson = objectMapper.writeValueAsString(entityIds);
            jdbcTemplate.update(
                    "INSERT INTO memory_injection_records (id, message_id, session_id, entity_ids_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), messageId, sessionId, entityIdsJson, Instant.now().toString());
        } catch (JsonProcessingException e) {
            log.warn("注入记录序列化失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    /**
     * 按消息 ID 查询注入的实体 ID 列表。
     *
     * @param messageId AI 回复消息 ID
     * @return 注入的实体 ID 列表，无记录时返回空列表
     */
    public List<String> findEntityIdsByMessageId(String messageId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT entity_ids_json FROM memory_injection_records WHERE message_id = ?",
                String.class, messageId);
        if (rows.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rows.getFirst(), STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("注入记录反序列化失败: messageId={}, error={}", messageId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 保存带类型的注入记录。
     *
     * @param traceId    执行 traceId
     * @param sessionId  会话 ID
     * @param entityIds  注入的实体 ID 列表
     * @param entityType 实体类型
     */
    public void saveWithType(String traceId, String sessionId,
                             List<String> entityIds, String entityType) {
        try {
            var entityIdsJson = objectMapper.writeValueAsString(entityIds);
            jdbcTemplate.update(
                    "INSERT INTO memory_injection_records (id, message_id, session_id, entity_ids_json, entity_type, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), traceId, sessionId, entityIdsJson, entityType, traceId, Instant.now().toString());
        } catch (JsonProcessingException e) {
            log.warn("注入记录序列化失败: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * 按 traceId 和实体类型查询注入的实体 ID 列表。
     *
     * @param traceId    执行 traceId
     * @param entityType 实体类型（如 "EXPERIENCE"）
     * @return 注入的实体 ID 列表
     */
    public List<String> findEntityIdsByTraceIdAndType(String traceId, String entityType) {
        var rows = jdbcTemplate.queryForList(
                "SELECT entity_ids_json FROM memory_injection_records WHERE trace_id = ? AND entity_type = ?",
                String.class, traceId, entityType);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 合并所有匹配记录的 entityIds
        return rows.stream()
                .flatMap(json -> {
                    try {
                        List<String> ids = objectMapper.readValue(json, STRING_LIST_TYPE);
                        return ids.stream();
                    } catch (JsonProcessingException e) {
                        log.warn("注入记录反序列化失败: traceId={}, error={}", traceId, e.getMessage());
                        return java.util.stream.Stream.empty();
                    }
                })
                .distinct()
                .toList();
    }
}
