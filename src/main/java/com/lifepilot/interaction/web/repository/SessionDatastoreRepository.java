package com.lifepilot.interaction.web.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 会话-datastore 关联表数据访问层。
 *
 * @author zsg
 * @since 2026-03-26
 */
@Repository
public class SessionDatastoreRepository {

    private final JdbcTemplate jdbcTemplate;

    public SessionDatastoreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findDatastoreIdsBySessionId(String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT datastore_id FROM session_datastores WHERE session_id = ?",
                String.class,
                sessionId
        );
    }

    public void addAssociation(String sessionId, String datastoreId) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO session_datastores (session_id, datastore_id) VALUES (?, ?)",
                sessionId,
                datastoreId
        );
    }

    public void removeAssociation(String sessionId, String datastoreId) {
        jdbcTemplate.update(
                "DELETE FROM session_datastores WHERE session_id = ? AND datastore_id = ?",
                sessionId,
                datastoreId
        );
    }

    public void removeAllAssociations(String sessionId) {
        jdbcTemplate.update(
                "DELETE FROM session_datastores WHERE session_id = ?",
                sessionId
        );
    }

    public void setAssociations(String sessionId, List<String> datastoreIds) {
        removeAllAssociations(sessionId);
        if (datastoreIds == null || datastoreIds.isEmpty()) {
            return;
        }
        for (String datastoreId : datastoreIds) {
            addAssociation(sessionId, datastoreId);
        }
    }
}
