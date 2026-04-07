package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.util.KnowledgeQueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 分块数据访问层 - 基于 JdbcTemplate 操作 document_chunks 表。
 *
 * <p>提供文档分块的批量插入、按文档查询、删除和计数操作，
 * JSON 列通过 Jackson ObjectMapper 序列化/反序列化。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkRepository.class);

    private static final String INSERT_SQL = """
            INSERT INTO document_chunks (
                id, document_id, knowledge_base_id, content, context_prefix,
                chunk_index, start_offset, end_offset, token_count, content_hash,
                heading_hierarchy_json, page_number, metadata_json,
                source_type, source_datastore_id, source_collection_id,
                parent_chunk_id, chunk_level, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<DocumentChunk> rowMapper;

    public DocumentChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    /**
     * 批量保存文档分块。
     *
     * <p>使用 {@link JdbcTemplate#batchUpdate(String, BatchPreparedStatementSetter)}
     * 在单次事务内批量插入所有分块。</p>
     *
     * @param chunks 分块列表
     */
    public void saveAll(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                DocumentChunk chunk = chunks.get(i);
                ps.setString(1, chunk.id());
                ps.setString(2, chunk.documentId());
                ps.setString(3, chunk.knowledgeBaseId());
                ps.setString(4, chunk.content());
                ps.setString(5, chunk.contextPrefix().orElse(null));
                ps.setInt(6, chunk.chunkIndex());
                ps.setInt(7, chunk.startOffset());
                ps.setInt(8, chunk.endOffset());
                ps.setInt(9, chunk.tokenCount());
                ps.setString(10, chunk.contentHash());
                ps.setString(11, serializeList(chunk.headingHierarchy()));
                ps.setInt(12, chunk.pageNumber());
                ps.setString(13, serializeMap(chunk.metadata()));
                ps.setString(14, chunk.sourceType().name());
                ps.setString(15, chunk.sourceDatastoreId());
                ps.setString(16, chunk.sourceCollectionId());
                ps.setString(17, chunk.parentChunkId().orElse(null));
                ps.setInt(18, chunk.chunkLevel());
                ps.setString(19, now.toString());
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    /**
     * 根据文档 id 查找所有分块，按 chunk_index 升序排列。
     *
     * @param documentId 文档 id
     * @return 分块列表（按 chunk_index 排序）
     */
    public List<DocumentChunk> findByDocumentId(String documentId) {
        return jdbcTemplate.query(
                """
                SELECT id, document_id, knowledge_base_id, content, context_prefix,
                       chunk_index, start_offset, end_offset, token_count, content_hash,
                       heading_hierarchy_json, page_number, metadata_json,
                       source_type, source_datastore_id, source_collection_id,
                       parent_chunk_id, chunk_level
                FROM document_chunks WHERE document_id = ? ORDER BY chunk_index""",
                rowMapper, documentId);
    }

    /**
     * 根据文档 id 删除所有分块。
     *
     * @param documentId 文档 id
     */
    public void deleteByDocumentId(String documentId) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
    }

    /**
     * 按文档更新所有分块的领域归属。
     *
     * @param documentId  文档 id
     * @param datastoreId 新的 datastore 归属，可为空表示共享文档
     */
    public void updateSourceDatastoreIdByDocumentId(String documentId, String datastoreId) {
        jdbcTemplate.update(
                "UPDATE document_chunks SET source_datastore_id = ? WHERE document_id = ?",
                datastoreId,
                documentId
        );
    }

    /**
     * 根据文档 id 统计分块数量。
     *
     * @param documentId 文档 id
     * @return 分块数量
     */
    public int countByDocumentId(String documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
                Integer.class, documentId);
        return count != null ? count : 0;
    }

    /**
     * 根据文档 id 和 chunkIndex 范围查询分块，按 chunk_index 升序排列。
     *
     * @param documentId 文档 id
     * @param fromIndex  起始 chunkIndex（含）
     * @param toIndex    结束 chunkIndex（含）
     * @return 分块列表（按 chunk_index 排序）
     */
    public List<DocumentChunk> findByDocumentIdAndChunkIndexRange(String documentId,
                                                                   int fromIndex, int toIndex) {
        return jdbcTemplate.query(
                """
                SELECT id, document_id, knowledge_base_id, content, context_prefix,
                       chunk_index, start_offset, end_offset, token_count, content_hash,
                       heading_hierarchy_json, page_number, metadata_json,
                       source_type, source_datastore_id, source_collection_id,
                       parent_chunk_id, chunk_level
                FROM document_chunks WHERE document_id = ? AND chunk_index BETWEEN ? AND ? ORDER BY chunk_index""",
                rowMapper, documentId, fromIndex, toIndex);
    }


    /**
     * 根据分块 ID 查询其 chunkIndex（用于上下文窗口扩展）。
     *
     * <p>仅查询单个字段，避免加载同文档的所有分块。</p>
     *
     * @param chunkId 分块 ID
     * @return chunkIndex，不存在时返回 empty
     */
    public OptionalInt findChunkIndexById(String chunkId) {
        List<Integer> results = jdbcTemplate.queryForList(
                "SELECT chunk_index FROM document_chunks WHERE id = ?",
                Integer.class, chunkId);
        return results.isEmpty() ? OptionalInt.empty() : OptionalInt.of(results.getFirst());
    }

    /**
     * 根据分块 ID 查询其父分块 ID。
     *
     * @param chunkId 分块 ID
     * @return 父分块 ID，不存在或为空时返回 empty
     */
    public Optional<String> findParentChunkId(String chunkId) {
        List<String> results = jdbcTemplate.queryForList(
                "SELECT parent_chunk_id FROM document_chunks WHERE id = ?",
                String.class, chunkId);
        return results.stream().filter(s -> s != null && !s.isBlank()).findFirst();
    }

    /**
     * 根据分块 ID 列表批量查询分块，按 chunk_index 升序排列。
     *
     * @param ids 分块 ID 列表
     * @return 分块列表（按 chunk_index 排序）
     */
    public List<DocumentChunk> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        return jdbcTemplate.query(
                "SELECT id, document_id, knowledge_base_id, content, context_prefix, " +
                "chunk_index, start_offset, end_offset, token_count, content_hash, " +
                "heading_hierarchy_json, page_number, metadata_json, " +
                "source_type, source_datastore_id, source_collection_id, " +
                "parent_chunk_id, chunk_level " +
                "FROM document_chunks WHERE id IN (" + placeholders + ") ORDER BY chunk_index",
                rowMapper, ids.toArray());
    }

    /**
     * 根据分块 ID 列表批量查询各分块的父分块 ID。
     *
     * @param chunkIds 分块 ID 列表
     * @return chunkId → parentChunkId 的映射（仅包含有父分块的条目）
     */
    public Map<String, String> findParentChunkIdsByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        var placeholders = chunkIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        var sql = "SELECT id, parent_chunk_id FROM document_chunks WHERE id IN (" + placeholders + ") AND parent_chunk_id IS NOT NULL";
        var result = new java.util.HashMap<String, String>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("id"), rs.getString("parent_chunk_id"));
        }, chunkIds.toArray());
        return result;
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 DocumentChunk record。 */
    private DocumentChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentChunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getString("knowledge_base_id"),
                rs.getString("content"),
                Optional.ofNullable(rs.getString("context_prefix")),
                rs.getInt("chunk_index"),
                rs.getInt("start_offset"),
                rs.getInt("end_offset"),
                rs.getInt("token_count"),
                rs.getString("content_hash"),
                deserializeList(rs.getString("heading_hierarchy_json")),
                rs.getInt("page_number"),
                deserializeMetadata(rs.getString("metadata_json")),
                KnowledgeQueryUtils.parseSourceType(rs.getString("source_type")),
                rs.getString("source_datastore_id"),
                rs.getString("source_collection_id"),
                Optional.ofNullable(rs.getString("parent_chunk_id")),
                rs.getInt("chunk_level")
        );
    }

    /** List<String> 序列化为 JSON 字符串。 */
    private String serializeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败，使用空数组: error={}", e.getMessage());
            return "[]";
        }
    }

    /** Map 序列化为 JSON 字符串。 */
    private String serializeMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败，使用空对象: error={}", e.getMessage());
            return "{}";
        }
    }

    /** JSON 字符串反序列化为 List<String>。 */
    private List<String> deserializeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : List.of();
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败，返回空列表: json={}, error={}", json, e.getMessage());
            return List.of();
        }
    }

    /** JSON 字符串反序列化为 Map<String, String>。 */
    private Map<String, String> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败，返回空 Map: json={}, error={}", json, e.getMessage());
            return Map.of();
        }
    }
}
