package com.lifepilot.datastore.repository;

import com.lifepilot.datastore.model.AggregationResult;
import com.lifepilot.datastore.model.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档仓储 — 基于 JdbcTemplate 操作 ds_documents 表。
 *
 * <p>提供文档的 CRUD 操作、FTS5 全文索引同步、动态查询和时序聚合执行。
 * FTS5 同步方法用于 NOTE 类型集合的全文搜索支持。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    /** ds_documents 表的核心列列表，用于所有 SELECT 查询。 */
    private static final String COLUMNS =
            "id, collection_id, data_json, recorded_at, source_type, knowledge_document_id, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Document> documentRowMapper;
    private final RowMapper<AggregationResult> aggregationRowMapper;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRowMapper = this::mapDocumentRow;
        this.aggregationRowMapper = this::mapAggregationRow;
    }

    // ---- 核心 CRUD ----

    /**
     * 插入新文档。
     *
     * @param document 文档数据（id、createdAt、updatedAt 由系统生成）
     * @return 生成的文档 UUID
     */
    public String insert(Document document) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String sourceType = document.sourceType() != null ? document.sourceType() : Document.SOURCE_TYPE_DATA;

        jdbcTemplate.update("""
                INSERT INTO ds_documents (
                    id, collection_id, data_json, recorded_at,
                    source_type, knowledge_document_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, document.collectionId(), document.dataJson(),
                document.recordedAt(), sourceType, document.knowledgeDocumentId(),
                now, now);

        log.info("文档创建成功: id={}, collectionId={}, sourceType={}", id, document.collectionId(), sourceType);
        return id;
    }

    /**
     * 根据 ID 查找文档。
     *
     * @param id 文档 ID
     * @return 文档 Optional，不存在时返回 empty
     */
    public Optional<Document> findById(String id) {
        List<Document> results = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_documents WHERE id = ?", documentRowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 分页查询指定集合下的文档。
     *
     * @param collectionId 集合 ID
     * @param offset       分页偏移
     * @param limit        每页数量
     * @return 文档列表
     */
    public List<Document> findByCollectionId(String collectionId, int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_documents WHERE collection_id = ? ORDER BY created_at ASC LIMIT ? OFFSET ?",
                documentRowMapper,
                collectionId, limit, offset);
    }

    /**
     * 查询指定集合下的所有文档（无分页，内部使用）。
     *
     * @param collectionId 集合 ID
     * @return 文档列表
     */
    public List<Document> findByCollectionId(String collectionId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_documents WHERE collection_id = ? ORDER BY created_at ASC",
                documentRowMapper,
                collectionId);
    }

    /**
     * 更新文档数据。
     *
     * @param id       文档 ID
     * @param dataJson 新的文档数据 JSON
     * @return 是否更新成功
     */
    public boolean update(String id, String dataJson) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE ds_documents SET data_json = ?, updated_at = ?
                WHERE id = ?
                """,
                dataJson, now, id);

        if (rows > 0) {
            log.info("文档更新成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 回填文件引用文档的知识库文档 ID。
     *
     * @param id                  Datastore 文档 ID
     * @param knowledgeDocumentId 知识库文档 ID
     * @return 是否更新成功
     */
    public boolean updateKnowledgeDocumentId(String id, String knowledgeDocumentId) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE ds_documents SET knowledge_document_id = ?, updated_at = ?
                WHERE id = ?
                """,
                knowledgeDocumentId, now, id);
        if (rows > 0) {
            log.info("知识库文档 ID 回填成功: id={}, knowledgeDocumentId={}", id, knowledgeDocumentId);
        }
        return rows > 0;
    }

    /**
     * 删除文档。
     *
     * @param id 文档 ID
     * @return 是否删除成功
     */
    public boolean delete(String id) {
        int rows = jdbcTemplate.update("DELETE FROM ds_documents WHERE id = ?", id);
        if (rows > 0) {
            log.info("文档删除成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 统计指定集合的文档数量。
     *
     * @param collectionId 集合 ID
     * @return 文档数量
     */
    public int countByCollection(String collectionId) {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ds_documents WHERE collection_id = ?",
                Integer.class, collectionId);
        return result != null ? result : 0;
    }

    // ---- FTS5 同步 ----

    /**
     * 向 FTS5 索引插入文档内容。
     *
     * @param documentId 文档 ID
     * @param content    文档文本内容
     */
    public void insertFts(String documentId, String content) {
        jdbcTemplate.update(
                "INSERT INTO ds_documents_fts (document_id, content) VALUES (?, ?)",
                documentId, content);
        log.debug("FTS5 索引插入: documentId={}", documentId);
    }

    /**
     * 更新 FTS5 索引中的文档内容。
     *
     * <p>FTS5 不支持直接 UPDATE，采用 DELETE + INSERT 策略。</p>
     *
     * @param documentId 文档 ID
     * @param content    新的文档文本内容
     */
    public void updateFts(String documentId, String content) {
        deleteFts(documentId);
        insertFts(documentId, content);
        log.debug("FTS5 索引更新: documentId={}", documentId);
    }

    /**
     * 从 FTS5 索引删除文档内容。
     *
     * @param documentId 文档 ID
     */
    public void deleteFts(String documentId) {
        jdbcTemplate.update(
                "DELETE FROM ds_documents_fts WHERE document_id = ?", documentId);
        log.debug("FTS5 索引删除: documentId={}", documentId);
    }

    /**
     * 批量删除指定集合所有文档的 FTS5 条目。
     *
     * @param collectionId 集合 ID
     * @return 删除的 FTS 条目数量
     */
    public int deleteFtsByCollectionId(String collectionId) {
        int rows = jdbcTemplate.update("""
                DELETE FROM ds_documents_fts
                WHERE document_id IN (SELECT id FROM ds_documents WHERE collection_id = ?)
                """, collectionId);
        if (rows > 0) {
            log.info("批量 FTS5 清理完成: collectionId={}, 删除条目数={}", collectionId, rows);
        }
        return rows;
    }

    /**
     * 全文搜索文档。
     *
     * <p>通过 JOIN ds_documents_fts 与 ds_documents 表，
     * 按集合过滤并按相关性排序返回匹配文档。</p>
     *
     * @param collectionId 集合 ID
     * @param query        搜索关键词
     * @param limit        返回数量上限
     * @return 匹配的文档列表，按相关性排序
     */
    public List<Document> searchFts(String collectionId, String query, int limit) {
        return jdbcTemplate.query("""
                SELECT d.id, d.collection_id, d.data_json, d.recorded_at,
                       d.source_type, d.knowledge_document_id, d.created_at, d.updated_at
                FROM ds_documents d
                JOIN ds_documents_fts fts ON d.id = fts.document_id
                WHERE d.collection_id = ? AND ds_documents_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """,
                documentRowMapper, collectionId, query, limit);
    }

    // ---- 动态查询 ----

    /**
     * 执行动态 SQL 查询，返回文档列表。
     *
     * <p>由 QueryEngine 构建 SQL，本方法仅负责执行和结果映射。</p>
     *
     * @param sql    参数化 SQL 语句
     * @param params SQL 参数数组
     * @return 查询结果文档列表
     */
    public List<Document> query(String sql, Object[] params) {
        return jdbcTemplate.query(sql, documentRowMapper, params);
    }

    // ---- 时序聚合 ----

    /**
     * 执行聚合 SQL 查询，返回聚合结果列表。
     *
     * <p>由 AggregationEngine 构建 SQL，本方法仅负责执行和结果映射。</p>
     *
     * @param sql    参数化聚合 SQL 语句
     * @param params SQL 参数数组
     * @return 聚合结果列表
     */
    public List<AggregationResult> aggregate(String sql, Object[] params) {
        return jdbcTemplate.query(sql, aggregationRowMapper, params);
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 Document record。 */
    private Document mapDocumentRow(ResultSet rs, @SuppressWarnings("unused") int rowNum) throws SQLException {
        return new Document(
                rs.getString("id"),
                rs.getString("collection_id"),
                rs.getString("data_json"),
                rs.getString("recorded_at"),
                rs.getString("source_type"),
                rs.getString("knowledge_document_id"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    /** RowMapper：将 ResultSet 行映射为 AggregationResult record。 */
    private AggregationResult mapAggregationRow(ResultSet rs, @SuppressWarnings("unused") int rowNum) throws SQLException {
        return new AggregationResult(
                rs.getString(1),
                rs.getDouble(2)
        );
    }
}
