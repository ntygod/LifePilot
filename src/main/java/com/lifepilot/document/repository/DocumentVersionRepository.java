package com.lifepilot.document.repository;

import com.lifepilot.document.model.DocumentVersionRecord;
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
 * 文档版本访问层 —— 对齐 {@link SessionDocumentRepository} 风格：
 * 常量 SELECT_COLUMNS + 静态 RowMapper + JdbcTemplate 参数化写入。
 *
 * <p>每个文档一条初始 v0 + N 条 patch/rollback 版本；按 {@code versionNo} 升序读出形成版本链。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@Repository
public class DocumentVersionRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionRepository.class);

    private static final String SELECT_COLUMNS =
            "id, document_id, version_no, file_path, source, patch_summary, diff_json, created_at";

    private static final RowMapper<DocumentVersionRecord> ROW_MAPPER = (rs, rowNum) -> new DocumentVersionRecord(
            rs.getString("id"),
            rs.getString("document_id"),
            rs.getInt("version_no"),
            rs.getString("file_path"),
            rs.getString("source"),
            rs.getString("patch_summary"),
            rs.getString("diff_json"),
            Instant.parse(rs.getString("created_at"))
    );

    private final JdbcTemplate jdbcTemplate;

    public DocumentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String save(DocumentVersionRecord record) {
        String id = record.id() != null ? record.id() : UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO document_versions (id, document_id, version_no, file_path, source, " +
                        "patch_summary, diff_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, record.documentId(), record.versionNo(), record.filePath(),
                record.source(), record.patchSummary(), record.diffJson(),
                record.createdAt().toString());
        log.debug("保存文档版本：documentId={}, versionNo={}, source={}",
                record.documentId(), record.versionNo(), record.source());
        return id;
    }

    public List<DocumentVersionRecord> findByDocumentId(String documentId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM document_versions " +
                        "WHERE document_id = ? ORDER BY version_no ASC",
                ROW_MAPPER, documentId);
    }

    /** 分页变体：按 versionNo 升序取 [offset, offset+limit)。 */
    public List<DocumentVersionRecord> findByDocumentId(String documentId, int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM document_versions " +
                        "WHERE document_id = ? ORDER BY version_no ASC LIMIT ? OFFSET ?",
                ROW_MAPPER, documentId, limit, offset);
    }

    public int countByDocumentId(String documentId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_versions WHERE document_id = ?",
                Integer.class, documentId);
        return n == null ? 0 : n;
    }

    /** P2-14：列所有 file_path 用于 GC 扫描孤儿文件对比。 */
    public List<String> findAllFilePaths() {
        return jdbcTemplate.queryForList(
                "SELECT file_path FROM document_versions", String.class);
    }

    @Nullable
    public DocumentVersionRecord findByDocumentIdAndVersion(String documentId, int versionNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_COLUMNS + " FROM document_versions " +
                            "WHERE document_id = ? AND version_no = ?",
                    ROW_MAPPER, documentId, versionNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public int deleteByDocumentId(String documentId) {
        return jdbcTemplate.update(
                "DELETE FROM document_versions WHERE document_id = ?", documentId);
    }
}
