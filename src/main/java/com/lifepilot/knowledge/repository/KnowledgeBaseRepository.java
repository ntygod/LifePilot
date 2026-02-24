package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.model.KnowledgeBase;
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
 * 知识库数据访问层 - 基于 JdbcTemplate 操作 knowledge_bases 表。
 *
 * <p>提供知识库的 CRUD 操作，使用 upsert 语义保存，
 * JSON 列通过 Jackson ObjectMapper 序列化/反序列化。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class KnowledgeBaseRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<KnowledgeBase> rowMapper;

    public KnowledgeBaseRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }
    /**
     * 保存知识库（upsert 语义）。
     *
     * <p>如果 id 已存在则更新，否则插入新记录。</p>
     *
     * @param kb 知识库实例
     */
    public void save(KnowledgeBase kb) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_bases (
                    id, name, description, embedding_model, reranker_model,
                    chunking_strategy, chunking_config_json,
                    document_count, total_chunks, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    description = excluded.description,
                    embedding_model = excluded.embedding_model,
                    reranker_model = excluded.reranker_model,
                    chunking_strategy = excluded.chunking_strategy,
                    chunking_config_json = excluded.chunking_config_json,
                    document_count = excluded.document_count,
                    total_chunks = excluded.total_chunks,
                    updated_at = excluded.updated_at
                """,
                kb.id(),
                kb.name(),
                kb.description(),
                kb.embeddingModel(),
                kb.rerankerModel().orElse(null),
                kb.chunkingStrategy(),
                serializeMap(kb.chunkingConfig()),
                kb.documentCount(),
                kb.totalChunks(),
                kb.createdAt().toString(),
                kb.updatedAt().toString());
    }

    /**
     * 根据 id 查找知识库。
     *
     * @param id 知识库 id
     * @return 知识库 Optional，不存在时返回 empty
     */
    public Optional<KnowledgeBase> findById(String id) {
        List<KnowledgeBase> results = jdbcTemplate.query(
                "SELECT * FROM knowledge_bases WHERE id = ?",
                rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 查询所有知识库，按 created_at 降序排列。
     *
     * @return 知识库列表
     */
    public List<KnowledgeBase> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM knowledge_bases ORDER BY created_at DESC",
                rowMapper);
    }

    /**
     * 根据 id 删除知识库。
     *
     * @param id 知识库 id
     */
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM knowledge_bases WHERE id = ?", id);
    }

    /**
     * 更新知识库的文档数和分块数。
     *
     * @param id          知识库 id
     * @param docCount    文档数
     * @param totalChunks 总分块数
     */
    public void updateDocumentCount(String id, int docCount, int totalChunks) {
        jdbcTemplate.update(
                "UPDATE knowledge_bases SET document_count = ?, total_chunks = ?, updated_at = ? WHERE id = ?",
                docCount, totalChunks, Instant.now().toString(), id);
    }
    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 KnowledgeBase record。 */
    private KnowledgeBase mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeBase(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("embedding_model"),
                Optional.ofNullable(rs.getString("reranker_model")),
                rs.getString("chunking_strategy"),
                deserializeMap(rs.getString("chunking_config_json")),
                rs.getInt("document_count"),
                rs.getInt("total_chunks"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    /** Map 序列化为 JSON 字符串。 */
    private String serializeMap(Map<String, Object> map) {
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

    /** JSON 字符串反序列化为 Map。 */
    private Map<String, Object> deserializeMap(String json) {
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