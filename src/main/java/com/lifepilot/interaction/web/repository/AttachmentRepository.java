package com.lifepilot.interaction.web.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 附件数据访问层。
 *
 * <p>附件当前挂载在 transcript 条目上，数据库使用 {@code message_attachments.entry_id}
 * 存储归属条目 ID。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@Repository
public class AttachmentRepository {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AttachmentRecord(
            String id,
            String sessionId,
            String fileName,
            String filePath,
            long fileSize,
            String mimeType,
            String url
    ) {
    }

    /**
     * 保存附件信息。
     *
     * @param entryId 归属 transcript 条目 ID，可为空
     * @param sessionId 会话 ID
     * @param fileName 文件名
     * @param filePath 文件路径
     * @param fileSize 文件大小
     * @param mimeType MIME 类型
     * @param url 访问 URL
     * @return 附件 ID
     */
    public String saveForEntry(String entryId,
                               String sessionId,
                               String fileName,
                               String filePath,
                               long fileSize,
                               String mimeType,
                               String url) {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO message_attachments (id, entry_id, session_id, file_name, file_path, file_size, mime_type, url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, entryId, sessionId, fileName, filePath, fileSize, mimeType, url, createdAt);

        log.debug("保存附件: id={}, fileName={}, sessionId={}, entryId={}", id, fileName, sessionId, entryId);
        return id;
    }

    public AttachmentRecord findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, session_id, file_name, file_path, file_size, mime_type, url FROM message_attachments WHERE id = ?",
                    (rs, rowNum) -> new AttachmentRecord(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("file_name"),
                            rs.getString("file_path"),
                            rs.getLong("file_size"),
                            rs.getString("mime_type"),
                            rs.getString("url")
                    ),
                    id
            );
        } catch (EmptyResultDataAccessException e) {
            log.warn("未找到附件: id={}", id);
            return null;
        }
    }

    public boolean sessionExists(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_store WHERE session_id = ?",
                Integer.class,
                sessionId
        );
        return count != null && count > 0;
    }

    public List<AttachmentRecord> findByEntryId(String entryId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, file_name, file_path, file_size, mime_type, url FROM message_attachments WHERE entry_id = ?",
                (rs, rowNum) -> new AttachmentRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("mime_type"),
                        rs.getString("url")
                ),
                entryId
        );
    }

    public Map<String, List<AttachmentRecord>> findByEntryIds(List<String> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(entryIds.size(), "?"));
        String sql = "SELECT id, entry_id, session_id, file_name, file_path, file_size, mime_type, url "
                + "FROM message_attachments WHERE entry_id IN (" + placeholders + ")";

        record Row(String id, String entryId, String sessionId, String fileName,
                   String filePath, long fileSize, String mimeType, String url) {
        }

        List<Row> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Row(
                        rs.getString("id"),
                        rs.getString("entry_id"),
                        rs.getString("session_id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("mime_type"),
                        rs.getString("url")
                ),
                entryIds.toArray()
        );

        return rows.stream().collect(Collectors.groupingBy(
                Row::entryId,
                Collectors.mapping(
                        row -> new AttachmentRecord(
                                row.id(),
                                row.sessionId(),
                                row.fileName(),
                                row.filePath(),
                                row.fileSize(),
                                row.mimeType(),
                                row.url()
                        ),
                        Collectors.toList()
                )
        ));
    }

    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM message_attachments WHERE session_id = ?", sessionId);
    }

    /**
     * P3 工作副本流程：按 filePath 回写 file_size。
     *
     * <p>文档 patch / rollback 会生成新的 working 版本文件，SessionDocumentRepository
     * 已经由 Service 调 updateFilePath 同步；对应的 message_attachments 行（若存在）
     * 也需同步大小以避免 UI 显示旧值。返回受影响行数；路径无匹配行时返回 0，
     * 不视为错误（文档可能尚未挂到消息气泡）。</p>
     *
     * @param filePath 文件路径（匹配 file_path 列）
     * @param newSize  最新 file_size（字节）
     * @return 受影响行数
     */
    public int updateSizeByFilePath(String filePath, long newSize) {
        return jdbcTemplate.update(
                "UPDATE message_attachments SET file_size = ? WHERE file_path = ?",
                newSize, filePath);
    }

    /**
     * 回填孤儿附件的 entry_id —— 用于 Tool 生成产物时先入 entry_id=null 的场景，
     * Assistant entry 持久化后调用本方法把本会话所有 orphan 的 attachment 挂到该 entry。
     *
     * <p>仅影响 {@code entry_id IS NULL} 的记录，不覆盖已挂载的。
     * 设计假设：本方法在 assistant entry 刚持久化后立即调用，此时 session 内
     * 同轮 turn 的 orphan 仅来自该 turn 的工具产物。如果出现跨轮 orphan 遗留
     * （极少），也会被无害地挂到本轮 —— 可接受的近似，Phase 3 编辑链路引入
     * 更严格关联时可以加 turn_id 过滤。</p>
     *
     * @param sessionId 会话 ID
     * @param entryId   目标 assistant entry ID
     * @return 回填行数
     */
    public int backfillOrphanEntryIds(String sessionId, String entryId) {
        int rows = jdbcTemplate.update(
                "UPDATE message_attachments SET entry_id = ? " +
                        "WHERE session_id = ? AND entry_id IS NULL",
                entryId, sessionId);
        if (rows > 0) {
            log.debug("回填孤儿附件 entry_id：sessionId={}, entryId={}, rows={}",
                    sessionId, entryId, rows);
        }
        return rows;
    }

    public void copyForFork(Map<String, String> entryIdMapping, String targetSessionId) {
        if (entryIdMapping == null || entryIdMapping.isEmpty()
                || targetSessionId == null || targetSessionId.isBlank()) {
            return;
        }
        Map<String, List<AttachmentRecord>> attachmentsByEntryId =
                findByEntryIds(List.copyOf(entryIdMapping.keySet()));
        if (attachmentsByEntryId.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> mapping : entryIdMapping.entrySet()) {
            String sourceEntryId = mapping.getKey();
            String targetEntryId = mapping.getValue();
            if (targetEntryId == null || targetEntryId.isBlank()) {
                continue;
            }
            List<AttachmentRecord> attachments = attachmentsByEntryId.get(sourceEntryId);
            if (attachments == null || attachments.isEmpty()) {
                continue;
            }
            for (AttachmentRecord attachment : attachments) {
                saveForEntry(
                        targetEntryId,
                        targetSessionId,
                        attachment.fileName(),
                        attachment.filePath(),
                        attachment.fileSize(),
                        attachment.mimeType(),
                        attachment.url()
                );
            }
        }
    }
}
