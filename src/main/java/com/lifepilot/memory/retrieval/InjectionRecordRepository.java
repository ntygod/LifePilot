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
}
