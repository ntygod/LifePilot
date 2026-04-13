package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.util.KnowledgeQueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档数据访问层 - 基于 JdbcTemplate 操作 documents 表。
 *
 * <p>提供文档的 CRUD 操作和状态更新，使用 upsert 语义保存，
 * JSON 列通过 Jackson ObjectMapper 序列化/反序列化，
 * status 列通过 {@link DocumentStatus#valueOf(String)} 转换。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<Document> rowMapper;

    public DocumentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    /**
     * 保存文档（upsert 语义）。
     *
     * <p>如果 id 已存在则更新，否则插入新记录。</p>
     *
     * @param doc 文档实例
     */
    public void save(Document doc) {
        jdbcTemplate.update("""
                INSERT INTO documents (
                    id, knowledge_base_id, file_name, file_path, file_size,
                    mime_type, content_hash, status, chunk_count, entity_count,
                    error_message, last_processed_stage, metadata_json,
                    created_at, updated_at, source_type, source_key,
                    source_datastore_id, source_collection_id, source_ref_json,
                    content, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    knowledge_base_id = excluded.knowledge_base_id,
                    file_name = excluded.file_name,
                    file_path = excluded.file_path,
                    file_size = excluded.file_size,
                    mime_type = excluded.mime_type,
                    content_hash = excluded.content_hash,
                    status = excluded.status,
                    chunk_count = excluded.chunk_count,
                    entity_count = excluded.entity_count,
                    error_message = excluded.error_message,
                    last_processed_stage = excluded.last_processed_stage,
                    metadata_json = excluded.metadata_json,
                    source_type = excluded.source_type,
                    source_key = excluded.source_key,
                    source_datastore_id = excluded.source_datastore_id,
                    source_collection_id = excluded.source_collection_id,
                    source_ref_json = excluded.source_ref_json,
                    content = excluded.content,
                    recorded_at = excluded.recorded_at,
                    updated_at = excluded.updated_at
                """,
                doc.id(),
                doc.knowledgeBaseId(),
                doc.fileName(),
                doc.filePath(),
                doc.fileSize(),
                doc.mimeType(),
                doc.contentHash(),
                doc.status().name(),
                doc.chunkCount(),
                doc.entityCount(),
                doc.errorMessage(),
                doc.lastProcessedStage(),
                serializeObjectMap(doc.metadata()),
                doc.createdAt().toString(),
                doc.updatedAt().toString(),
                doc.sourceType().name(),
                doc.sourceKey(),
                doc.sourceDatastoreId(),
                doc.sourceCollectionId(),
                serializeObjectMap(doc.sourceRef()),
                doc.content(),
                doc.recordedAt());
    }

    /**
     * 根据 id 查找文档。
     *
     * @param id 文档 id
     * @return 文档 Optional，不存在时返回 empty
     */
    public Optional<Document> findById(String id) {
        List<Document> results = jdbcTemplate.query(
                "SELECT " + ALL_COLUMNS + " FROM documents WHERE id = ?",
                rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 根据知识库 id 查找所有文档。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 文档列表
     */
    public List<Document> findByKnowledgeBaseId(String knowledgeBaseId) {
        return jdbcTemplate.query(
                "SELECT " + ALL_COLUMNS + " FROM documents WHERE knowledge_base_id = ?",
                rowMapper, knowledgeBaseId);
    }

    /**
     * 根据来源键查找文档。
     */
    public Optional<Document> findByKnowledgeBaseIdAndSourceKey(String knowledgeBaseId, String sourceKey) {
        List<Document> results = jdbcTemplate.query(
                "SELECT " + ALL_COLUMNS + " FROM documents WHERE knowledge_base_id = ? AND source_key = ?",
                rowMapper, knowledgeBaseId, sourceKey);
        return results.stream().findFirst();
    }

    /**
     * 查询指定领域下的同步文档。
     */
    public List<Document> findByKnowledgeBaseIdAndSourceDatastoreIdAndSourceType(String knowledgeBaseId,
                                                                                  String datastoreId,
                                                                                  DocumentSourceType sourceType) {
        return jdbcTemplate.query(
                "SELECT " + ALL_COLUMNS +
                " FROM documents WHERE knowledge_base_id = ? AND source_datastore_id = ? AND source_type = ?",
                rowMapper,
                knowledgeBaseId,
                datastoreId,
                sourceType.name());
    }

    /**
     * 根据 id 删除文档。
     *
     * @param id 文档 id
     */
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM documents WHERE id = ?", id);
    }

    /**
     * 更新文档状态和错误消息。
     *
     * @param id           文档 id
     * @param status       新状态
     * @param errorMessage 错误消息（为空时清除）
     */
    public void updateStatus(String id, DocumentStatus status, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE documents SET status = ?, error_message = ?, updated_at = ? WHERE id = ?",
                status.name(), errorMessage, Instant.now().toString(), id);
    }

    /**
     * 更新文档内容哈希。
     *
     * @param id          文档 id
     * @param contentHash 新的内容哈希
     */
    public void updateContentHash(String id, String contentHash) {
        jdbcTemplate.update(
                "UPDATE documents SET content_hash = ?, updated_at = ? WHERE id = ?",
                contentHash, Instant.now().toString(), id);
    }

    /**
     * 更新文档分块数。
     *
     * @param id         文档 id
     * @param chunkCount 新的分块数
     */
    public void updateChunkCount(String id, int chunkCount) {
        jdbcTemplate.update(
                "UPDATE documents SET chunk_count = ?, updated_at = ? WHERE id = ?",
                chunkCount, Instant.now().toString(), id);
    }

    /**
     * 更新文档最后处理阶段。
     *
     * @param id    文档 id
     * @param stage 处理阶段名称
     */
    public void updateLastProcessedStage(String id, String stage) {
        jdbcTemplate.update(
                "UPDATE documents SET last_processed_stage = ?, updated_at = ? WHERE id = ?",
                stage, Instant.now().toString(), id);
    }

    /**
     * 按知识库 ID 和内容哈希查找文档 ID（用于重复检测）。
     *
     * <p>仅返回第一条匹配的文档 ID，避免加载完整文档对象。</p>
     *
     * @param kbId        知识库 ID
     * @param contentHash SHA-256 内容哈希
     * @return 匹配的文档 ID，不存在时返回 empty
     */
    public Optional<String> findIdByKnowledgeBaseIdAndContentHash(String kbId, String contentHash) {
        List<String> results = jdbcTemplate.queryForList(
                "SELECT id FROM documents WHERE knowledge_base_id = ? AND content_hash = ? LIMIT 1",
                String.class, kbId, contentHash);
        return results.stream().findFirst();
    }

    /**
     * 知识库统计结果 — 文档数和分块总数。
     *
     * @param documentCount 文档数量
     * @param totalChunks   分块总数
     */
    public record KnowledgeBaseStats(int documentCount, int totalChunks) {}

    /**
     * 按知识库 ID 统计文档数量和分块总数（聚合查询）。
     *
     * <p>使用 SQL 聚合函数避免加载全部文档对象到内存。</p>
     *
     * @param kbId 知识库 ID
     * @return 统计结果
     */
    public KnowledgeBaseStats countByKnowledgeBaseId(String kbId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*), COALESCE(SUM(chunk_count), 0) FROM documents WHERE knowledge_base_id = ?",
                (rs, rowNum) -> new KnowledgeBaseStats(rs.getInt(1), rs.getInt(2)),
                kbId);
    }

    // ---- Datastore 文档操作 ----

    /** 按 source_datastore_id 查询文档（分页）。 */
    public List<Document> findBySourceDatastoreId(String datastoreId, int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT " + ALL_COLUMNS + " FROM documents WHERE source_datastore_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                rowMapper, datastoreId, limit, offset);
    }

    /** 按 source_datastore_id 统计文档数量。 */
    public int countBySourceDatastoreId(String datastoreId) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE source_datastore_id = ?",
                Integer.class, datastoreId);
        return result != null ? result : 0;
    }

    /** 更新文档正文和内容哈希 — 用于 Datastore 文档 content 变更后触发重新 ingest。 */
    public void updateContent(String id, String content, String contentHash) {
        jdbcTemplate.update(
                "UPDATE documents SET content = ?, content_hash = ?, updated_at = ? WHERE id = ?",
                content, contentHash, Instant.now().toString(), id);
    }

    /** 仅更新 metadata_json — 不触发重新 ingest。 */
    public void updateMetadataJson(String id, String metadataJson) {
        jdbcTemplate.update(
                "UPDATE documents SET metadata_json = ?, updated_at = ? WHERE id = ?",
                metadataJson, Instant.now().toString(), id);
    }

    /** 执行 QueryEngine 生成的原始 SQL 查询。 */
    public List<Document> queryRaw(String sql, Object[] params) {
        return jdbcTemplate.query(sql, rowMapper, params);
    }

    /** 执行 AggregationEngine 生成的原始 SQL 聚合查询。 */
    public List<com.lifepilot.datastore.model.AggregationResult> aggregateRaw(String sql, Object[] params) {
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new com.lifepilot.datastore.model.AggregationResult(
                        rs.getString("time_bucket"),
                        rs.getDouble(2)
                ), params);
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 Document record。 */
    /** 完整 SELECT 列列表 — 包含所有列用于标准查询。 */
    private static final String ALL_COLUMNS =
            "id, knowledge_base_id, file_name, file_path, file_size, mime_type, " +
            "content_hash, status, chunk_count, entity_count, error_message, " +
            "last_processed_stage, metadata_json, created_at, updated_at, " +
            "source_type, source_key, source_datastore_id, source_collection_id, " +
            "source_ref_json, content, recorded_at";

    private Document mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Document(
                rs.getString("id"),
                rs.getString("knowledge_base_id"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getLong("file_size"),
                rs.getString("mime_type"),
                rs.getString("content_hash"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getInt("chunk_count"),
                rs.getInt("entity_count"),
                rs.getString("error_message"),
                rs.getString("last_processed_stage"),
                deserializeMetadata(rs.getString("metadata_json")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                KnowledgeQueryUtils.parseSourceType(rs.getString("source_type")),
                rs.getString("source_key"),
                rs.getString("source_datastore_id"),
                rs.getString("source_collection_id"),
                deserializeObjectMap(rs.getString("source_ref_json")),
                rs.getString("content"),
                rs.getString("recorded_at")
        );
    }

    /** Map 序列化为 JSON 字符串。 */
    private String serializeObjectMap(Map<String, Object> map) {
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

    /** JSON 字符串反序列化为 Map<String, Object>，支持数值类型。 */
    private Map<String, Object> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败，返回空 Map: json={}, error={}", json, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> deserializeObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败，返回空 Map: json={}, error={}", json, e.getMessage());
            return Map.of();
        }
    }
}
