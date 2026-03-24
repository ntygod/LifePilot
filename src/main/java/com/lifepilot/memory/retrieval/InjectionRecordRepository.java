package com.lifepilot.memory.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 注入记录数据访问层。
 *
 * <p>记录每次回答或经验注入对应的 provenance，区分 transcript 条目来源与 trace 来源。</p>
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
     * 保存基于 transcript 条目的注入记录。
     *
     * @param sourceEntryId transcript 条目 ID
     * @param sessionId 会话 ID
     * @param sourceTraceId 来源 traceId
     * @param entityIds 注入的实体 ID 列表
     */
    public void save(String sourceEntryId,
                     String sessionId,
                     @Nullable String sourceTraceId,
                     List<String> entityIds) {
        try {
            var entityIdsJson = objectMapper.writeValueAsString(entityIds);
            jdbcTemplate.update(
                    """
                    INSERT INTO memory_injection_records (
                        id, source_entry_id, session_id, entity_ids_json,
                        entity_type, source_trace_id, created_at
                    ) VALUES (?, ?, ?, ?, 'GENERAL', ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    sourceEntryId,
                    sessionId,
                    entityIdsJson,
                    normalizeBlank(sourceTraceId),
                    Instant.now().toString()
            );
        } catch (JsonProcessingException e) {
            log.warn("注入记录序列化失败: sourceEntryId={}, error={}", sourceEntryId, e.getMessage());
        }
    }

    /**
     * 按 transcript 条目 ID 查询注入的实体 ID 列表。
     *
     * @param sourceEntryId transcript 条目 ID
     * @return 注入的实体 ID 列表
     */
    public List<String> findEntityIdsBySourceEntryId(String sourceEntryId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT entity_ids_json FROM memory_injection_records WHERE source_entry_id = ?",
                String.class,
                sourceEntryId
        );
        return deserializeEntityIds(rows, "sourceEntryId=" + sourceEntryId);
    }

    /**
     * 保存基于 trace 的注入记录。
     *
     * @param sourceTraceId 来源 traceId
     * @param sessionId 会话 ID
     * @param entityIds 注入的实体 ID 列表
     * @param entityType 实体类型
     */
    public void saveWithType(String sourceTraceId,
                             String sessionId,
                             List<String> entityIds,
                             String entityType) {
        try {
            var entityIdsJson = objectMapper.writeValueAsString(entityIds);
            jdbcTemplate.update(
                    """
                    INSERT INTO memory_injection_records (
                        id, source_entry_id, session_id, entity_ids_json,
                        entity_type, source_trace_id, created_at
                    ) VALUES (?, NULL, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    sessionId,
                    entityIdsJson,
                    entityType,
                    sourceTraceId,
                    Instant.now().toString()
            );
        } catch (JsonProcessingException e) {
            log.warn("注入记录序列化失败: sourceTraceId={}, error={}", sourceTraceId, e.getMessage());
        }
    }

    /**
     * 按 traceId 和实体类型查询注入的实体 ID 列表。
     *
     * @param sourceTraceId 来源 traceId
     * @param entityType 实体类型
     * @return 去重后的实体 ID 列表
     */
    public List<String> findEntityIdsBySourceTraceIdAndType(String sourceTraceId, String entityType) {
        var rows = jdbcTemplate.queryForList(
                """
                SELECT entity_ids_json
                FROM memory_injection_records
                WHERE source_trace_id = ? AND entity_type = ?
                """,
                String.class,
                sourceTraceId,
                entityType
        );
        return rows.stream()
                .flatMap(json -> deserializeSingleRow(json, "sourceTraceId=" + sourceTraceId).stream())
                .distinct()
                .toList();
    }

    private List<String> deserializeEntityIds(List<String> rows, String provenance) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .flatMap(json -> deserializeSingleRow(json, provenance).stream())
                .distinct()
                .toList();
    }

    private List<String> deserializeSingleRow(String json, String provenance) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("注入记录反序列化失败: provenance={}, error={}", provenance, e.getMessage());
            return List.of();
        }
    }

    @Nullable
    private String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
