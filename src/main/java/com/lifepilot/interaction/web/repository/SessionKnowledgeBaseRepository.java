package com.lifepilot.interaction.web.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话-知识库关联表数据访问层。
 *
 * @author zsg
 * @since 2026-02-28
 */
@Repository
public class SessionKnowledgeBaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public SessionKnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取会话关联的知识库 ID 列表。
     *
     * @param sessionId 会话 ID
     * @return 知识库 ID 列表
     */
    public List<String> findKnowledgeBaseIdsBySessionId(String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT knowledge_base_id FROM session_knowledge_bases WHERE session_id = ?",
                String.class, sessionId);
    }

    /**
     * 添加会话-知识库关联。
     *
     * @param sessionId       会话 ID
     * @param knowledgeBaseId 知识库 ID
     */
    public void addAssociation(String sessionId, String knowledgeBaseId) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO session_knowledge_bases (session_id, knowledge_base_id) VALUES (?, ?)",
                sessionId, knowledgeBaseId);
    }

    /**
     * 删除会话-知识库关联。
     *
     * @param sessionId       会话 ID
     * @param knowledgeBaseId 知识库 ID
     */
    public void removeAssociation(String sessionId, String knowledgeBaseId) {
        jdbcTemplate.update(
                "DELETE FROM session_knowledge_bases WHERE session_id = ? AND knowledge_base_id = ?",
                sessionId, knowledgeBaseId);
    }

    /**
     * 删除会话的所有知识库关联。
     *
     * @param sessionId 会话 ID
     */
    public void removeAllAssociations(String sessionId) {
        jdbcTemplate.update(
                "DELETE FROM session_knowledge_bases WHERE session_id = ?",
                sessionId);
    }

    /**
     * 批量设置会话的知识库关联（先删除旧的，再添加新的）。
     *
     * @param sessionId        会话 ID
     * @param knowledgeBaseIds 知识库 ID 列表
     */
    public void setAssociations(String sessionId, List<String> knowledgeBaseIds) {
        // 删除旧的关联
        removeAllAssociations(sessionId);
        
        // 添加新的关联
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            for (String kbId : knowledgeBaseIds) {
                addAssociation(sessionId, kbId);
            }
        }
    }
}
