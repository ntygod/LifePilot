package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文档产物元数据访问层。
 *
 * <p>落盘 + 入表流程：工具层先 write 文件到 {@code DocumentProperties.storageDir}，
 * 再通过本 Repository {@link #save} 记录元数据。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@Repository
public class DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存文档元数据。若 record.id 为 null 自动生成 UUID。
     *
     * @return 持久化后的 document id
     */
    public String save(DocumentRecord record) {
        String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO documents (id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, record.sessionId(), record.entryId(),
                record.fileName(), record.filePath(), record.fileSize(),
                record.mimeType(), record.origin(), record.createdAt().toString());
        log.debug("保存文档：id={}, fileName={}, origin={}", id, record.fileName(), record.origin());
        return id;
    }

    @Nullable
    public DocumentRecord findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, created_at " +
                            "FROM documents WHERE id = ?",
                    (rs, rowNum) -> new DocumentRecord(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("entry_id"),
                            rs.getString("file_name"),
                            rs.getString("file_path"),
                            rs.getLong("file_size"),
                            rs.getString("mime_type"),
                            rs.getString("origin"),
                            Instant.parse(rs.getString("created_at"))
                    ),
                    id);
        } catch (EmptyResultDataAccessException e) {
            log.warn("未找到文档：id={}", id);
            return null;
        }
    }

    public List<DocumentRecord> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, entry_id, file_name, file_path, file_size, mime_type, origin, created_at " +
                        "FROM documents WHERE session_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> new DocumentRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("entry_id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("mime_type"),
                        rs.getString("origin"),
                        Instant.parse(rs.getString("created_at"))
                ),
                sessionId);
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM documents WHERE session_id = ?", sessionId);
    }
}
