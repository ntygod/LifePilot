package com.lifepilot.document.repository;

import com.lifepilot.document.model.SessionDocumentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话级文档产物元数据访问层。
 *
 * <p>P3 扩展：SELECT 增补 {@code source_path} / {@code latest_version}；
 * 增加 {@link #updateLatestVersion} / {@link #updateFilePath} /
 * {@link #findBySessionAndSourcePath} / {@link #deleteById} 支持工作副本生命周期。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@Repository
public class SessionDocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionDocumentRepository.class);

    private static final String SELECT_COLUMNS =
            "id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, " +
                    "source_path, latest_version, created_at";

    private static final RowMapper<SessionDocumentRecord> ROW_MAPPER = (rs, rowNum) -> new SessionDocumentRecord(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("entry_id"),
            rs.getString("file_name"),
            rs.getString("file_path"),
            rs.getLong("file_size"),
            rs.getString("mime_type"),
            rs.getString("origin"),
            rs.getString("source_path"),
            rs.getInt("latest_version"),
            Instant.parse(rs.getString("created_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public SessionDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String save(SessionDocumentRecord record) {
        String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO session_documents (id, session_id, entry_id, file_name, file_path, " +
                        "file_size, mime_type, origin, source_path, latest_version, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, record.sessionId(), record.entryId(),
                record.fileName(), record.filePath(), record.fileSize(),
                record.mimeType(), record.origin(),
                record.sourcePath(), record.latestVersion(),
                record.createdAt().toString());
        log.debug("保存文档：id={}, fileName={}, origin={}, sourcePath={}",
                id, record.fileName(), record.origin(), record.sourcePath());
        return id;
    }

    @Nullable
    public SessionDocumentRecord findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM session_documents WHERE id = ?",
                    ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            log.warn("未找到文档：id={}", id);
            return null;
        }
    }

    public List<SessionDocumentRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM session_documents WHERE session_id = ? " +
                        "ORDER BY created_at DESC",
                ROW_MAPPER, sessionId);
    }

    @Nullable
    public SessionDocumentRecord findBySessionAndSourcePath(String sessionId, String sourcePath) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM session_documents " +
                            "WHERE session_id = ? AND source_path = ?",
                    ROW_MAPPER, sessionId, sourcePath);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public int updateLatestVersion(String id, int newVersion) {
        return jdbcTemplate.update(
                "UPDATE session_documents SET latest_version = ? WHERE id = ?",
                newVersion, id);
    }

    public int updateFilePath(String id, String newFilePath, long newFileSize) {
        return jdbcTemplate.update(
                "UPDATE session_documents SET file_path = ?, file_size = ? WHERE id = ?",
                newFilePath, newFileSize, id);
    }

    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM session_documents WHERE id = ?", id);
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM session_documents WHERE session_id = ?", sessionId);
    }
}
