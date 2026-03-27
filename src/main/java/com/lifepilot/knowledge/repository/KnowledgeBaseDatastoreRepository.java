package com.lifepilot.knowledge.repository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 知识库-datastore 挂载关系仓储。
 *
 * @author zsg
 * @since 2026-03-26
 */
public class KnowledgeBaseDatastoreRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseDatastoreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findDatastoreIdsByKnowledgeBaseId(String knowledgeBaseId) {
        return jdbcTemplate.queryForList(
                "SELECT datastore_id FROM knowledge_base_datastores WHERE knowledge_base_id = ? ORDER BY created_at ASC",
                String.class,
                knowledgeBaseId
        );
    }

    public List<String> findKnowledgeBaseIdsByDatastoreId(String datastoreId) {
        return jdbcTemplate.queryForList(
                "SELECT knowledge_base_id FROM knowledge_base_datastores WHERE datastore_id = ? ORDER BY created_at ASC",
                String.class,
                datastoreId
        );
    }

    public void addAssociation(String knowledgeBaseId, String datastoreId) {
        jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO knowledge_base_datastores (knowledge_base_id, datastore_id, created_at)
                VALUES (?, ?, ?)
                """,
                knowledgeBaseId,
                datastoreId,
                Instant.now().toString()
        );
    }

    public void removeAssociation(String knowledgeBaseId, String datastoreId) {
        jdbcTemplate.update(
                "DELETE FROM knowledge_base_datastores WHERE knowledge_base_id = ? AND datastore_id = ?",
                knowledgeBaseId,
                datastoreId
        );
    }

    public void setAssociations(String knowledgeBaseId, Set<String> datastoreIds) {
        jdbcTemplate.update(
                "DELETE FROM knowledge_base_datastores WHERE knowledge_base_id = ?",
                knowledgeBaseId
        );
        if (datastoreIds == null || datastoreIds.isEmpty()) {
            return;
        }
        datastoreIds.forEach(datastoreId -> addAssociation(knowledgeBaseId, datastoreId));
    }
}
