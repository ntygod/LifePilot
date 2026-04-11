package com.lifepilot.memory.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable memory 文档仓储。
 *
 * @author zsg
 * @since 2026-03-23
 */
@Repository
public class MemoryDocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryDocumentRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStoreRepository sessionStoreRepository;

    public record MemoryDocumentRow(
            String id,
            String namespace,
            String docType,
            String title,
            String pathLikeKey,
            String contentMarkdown,
            @Nullable String sourceSessionId,
            @Nullable String sourceEntryId,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record MemoryDocumentChunkRow(
            String id,
            String documentId,
            int chunkIndex,
            String contentText,
            int tokenEstimate,
            Instant createdAt
    ) {
    }

    public MemoryDocumentRepository(JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    SessionStoreRepository sessionStoreRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sessionStoreRepository = sessionStoreRepository;
    }

    @Autowired
    public MemoryDocumentRepository(JdbcTemplate jdbcTemplate,
                                    SessionStoreRepository sessionStoreRepository) {
        this(jdbcTemplate, new ObjectMapper(), sessionStoreRepository);
    }

    public String upsert(String namespace,
                         String docType,
                         String title,
                         String pathLikeKey,
                         String contentMarkdown,
                         @Nullable String sourceSessionId,
                         @Nullable String sourceEntryId,
                         @Nullable Instant updatedAt) {
        if (sourceSessionId != null && !sourceSessionId.isBlank()) {
            sessionStoreRepository.ensureSessionShell(sourceSessionId);
        }
        Instant now = updatedAt != null ? updatedAt : Instant.now();
        Optional<MemoryDocumentRow> existing = findByNamespaceAndPathLikeKey(namespace, pathLikeKey);
        String id = existing.map(MemoryDocumentRow::id).orElseGet(() -> UUID.randomUUID().toString());
        Instant createdAt = existing.map(MemoryDocumentRow::createdAt).orElse(now);

        jdbcTemplate.update("""
                INSERT INTO memory_documents (
                    id, namespace, doc_type, title, path_like_key,
                    content_markdown, source_session_id, source_entry_id,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(namespace, path_like_key) DO UPDATE SET
                    doc_type = excluded.doc_type,
                    title = excluded.title,
                    content_markdown = excluded.content_markdown,
                    source_session_id = excluded.source_session_id,
                    source_entry_id = excluded.source_entry_id,
                    updated_at = excluded.updated_at
                """,
                id,
                namespace,
                docType,
                title,
                pathLikeKey,
                contentMarkdown,
                sourceSessionId,
                sourceEntryId,
                createdAt.toString(),
                now.toString()
        );
        return id;
    }

    public void replaceChunks(String documentId, List<String> chunks, @Nullable Instant createdAt) {
        Instant timestamp = createdAt != null ? createdAt : Instant.now();
        jdbcTemplate.update("DELETE FROM memory_document_chunks WHERE document_id = ?", documentId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i) != null ? chunks.get(i) : "";
            jdbcTemplate.update("""
                    INSERT INTO memory_document_chunks (
                        id, document_id, chunk_index, content_text, token_estimate, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    documentId,
                    i,
                    content,
                    estimateTokens(content),
                    timestamp.toString()
            );
        }
    }

    public Optional<MemoryDocumentRow> findByNamespaceAndPathLikeKey(String namespace, String pathLikeKey) {
        List<MemoryDocumentRow> rows = jdbcTemplate.query("""
                SELECT id, namespace, doc_type, title, path_like_key, content_markdown, source_session_id, source_entry_id, created_at, updated_at FROM memory_documents
                WHERE namespace = ? AND path_like_key = ?
                """,
                this::mapDocumentRow,
                namespace,
                pathLikeKey
        );
        return rows.stream().findFirst();
    }

    public List<MemoryDocumentRow> listByNamespace(String namespace) {
        return jdbcTemplate.query("""
                SELECT id, namespace, doc_type, title, path_like_key, content_markdown, source_session_id, source_entry_id, created_at, updated_at FROM memory_documents
                WHERE namespace = ?
                ORDER BY updated_at DESC, id DESC
                """,
                this::mapDocumentRow,
                namespace
        );
    }

    public List<MemoryDocumentChunkRow> findChunks(String documentId) {
        return jdbcTemplate.query("""
                SELECT id, document_id, chunk_index, content_text, token_estimate, created_at FROM memory_document_chunks
                WHERE document_id = ?
                ORDER BY chunk_index ASC
                """,
                this::mapChunkRow,
                documentId
        );
    }

    private MemoryDocumentRow mapDocumentRow(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryDocumentRow(
                rs.getString("id"),
                rs.getString("namespace"),
                rs.getString("doc_type"),
                rs.getString("title"),
                rs.getString("path_like_key"),
                rs.getString("content_markdown"),
                rs.getString("source_session_id"),
                rs.getString("source_entry_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private MemoryDocumentChunkRow mapChunkRow(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryDocumentChunkRow(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getInt("chunk_index"),
                rs.getString("content_text"),
                rs.getInt("token_estimate"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    @SuppressWarnings("unused")
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("对象序列化失败: error={}", e.getMessage());
            return "{}";
        }
    }
}
