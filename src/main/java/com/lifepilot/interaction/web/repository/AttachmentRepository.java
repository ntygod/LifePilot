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
 * 消息附件数据访问层。
 *
 * <p>基于 JdbcTemplate 操作 message_attachments 表，提供附件的保存和查询操作。
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

    /**
     * 附件记录模型。
     *
     * @param id        附件 ID
     * @param sessionId 会话 ID
     * @param fileName  文件名
     * @param filePath  文件存储路径
     * @param fileSize  文件大小（字节）
     * @param mimeType  MIME 类型
     * @param url       文件访问 URL
     */
    public record AttachmentRecord(
            String id,
            String sessionId,
            String fileName,
            String filePath,
            long fileSize,
            String mimeType,
            String url
    ) {}

    /**
     * 保存附件信息。
     *
     * @param messageId 消息 ID（可为 null，上传时可能还没有消息）
     * @param sessionId 会话 ID
     * @param fileName  文件名
     * @param filePath  文件存储路径
     * @param fileSize  文件大小（字节）
     * @param mimeType  MIME 类型
     * @param url       文件访问 URL（可为 null）
     * @return 附件 ID
     */
    public String save(String messageId, String sessionId, String fileName, String filePath,
                       long fileSize, String mimeType, String url) {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO message_attachments (id, message_id, session_id, file_name, file_path, file_size, mime_type, url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, messageId, sessionId, fileName, filePath, fileSize, mimeType, url, createdAt);

        log.debug("保存附件: id={}, fileName={}, sessionId={}", id, fileName, sessionId);
        return id;
    }

    /**
     * 根据附件 ID 查询附件记录。
     *
     * @param id 附件 ID
     * @return 附件记录，未找到时返回 null
     */
    public AttachmentRecord findById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, session_id, file_name, file_path, file_size, mime_type, url " +
                            "FROM message_attachments WHERE id = ?",
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

    /**
     * 检查会话是否存在。
     *
     * @param sessionId 会话 ID
     * @return 如果会话存在返回 true，否则返回 false
     */
    public boolean sessionExists(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_sessions WHERE id = ?",
                Integer.class, sessionId);
        return count != null && count > 0;
    }

    /**
     * 根据消息 ID 查询关联的附件列表。
     *
     * @param messageId 消息 ID
     * @return 附件记录列表
     */
    public List<AttachmentRecord> findByMessageId(String messageId) {
        return jdbcTemplate.query(
                "SELECT id, session_id, file_name, file_path, file_size, mime_type, url " +
                        "FROM message_attachments WHERE message_id = ?",
                (rs, rowNum) -> new AttachmentRecord(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("mime_type"),
                        rs.getString("url")
                ),
                messageId
        );
    }

    /**
     * 根据多个消息 ID 批量查询附件，按 message_id 分组返回。
     *
     * @param messageIds 消息 ID 列表
     * @return message_id → 附件列表的映射
     */
    public Map<String, List<AttachmentRecord>> findByMessageIds(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(messageIds.size(), "?"));
        String sql = "SELECT id, message_id, session_id, file_name, file_path, file_size, mime_type, url " +
                "FROM message_attachments WHERE message_id IN (" + placeholders + ")";

        record Row(String id, String messageId, String sessionId, String fileName,
                   String filePath, long fileSize, String mimeType, String url) {}

        List<Row> rows = jdbcTemplate.query(sql,
                (rs, rowNum) -> new Row(
                        rs.getString("id"),
                        rs.getString("message_id"),
                        rs.getString("session_id"),
                        rs.getString("file_name"),
                        rs.getString("file_path"),
                        rs.getLong("file_size"),
                        rs.getString("mime_type"),
                        rs.getString("url")
                ),
                messageIds.toArray()
        );

        return rows.stream().collect(Collectors.groupingBy(
                Row::messageId,
                Collectors.mapping(
                        r -> new AttachmentRecord(r.id(), r.sessionId(), r.fileName(),
                                r.filePath(), r.fileSize(), r.mimeType(), r.url()),
                        Collectors.toList()
                )
        ));
    }
}
