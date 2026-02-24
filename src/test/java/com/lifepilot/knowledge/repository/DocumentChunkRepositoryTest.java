package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.model.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentChunkRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证 saveAll/findByDocumentId/
 * countByDocumentId/deleteByDocumentId round-trip 及排序。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentChunkRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-chunk-repo-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-chunk-repo-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private DocumentChunkRepository chunkRepository;
    private KnowledgeBaseRepository kbRepository;
    private DocumentRepository docRepository;

    private String knowledgeBaseId;
    private String documentId;

    @BeforeEach
    void setUp() {
        chunkRepository = new DocumentChunkRepository(jdbcTemplate, objectMapper);
        kbRepository = new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
        docRepository = new DocumentRepository(jdbcTemplate, objectMapper);
        // 清理测试数据
        jdbcTemplate.execute("DELETE FROM document_chunks");
        jdbcTemplate.execute("DELETE FROM documents");
        jdbcTemplate.execute("DELETE FROM knowledge_bases");
        // 创建前置知识库和文档
        var kb = KnowledgeBase.create("测试知识库", "描述", "model");
        kbRepository.save(kb);
        knowledgeBaseId = kb.id();

        var now = Instant.now();
        var doc = new Document(
                UUID.randomUUID().toString(), knowledgeBaseId, "test.md", "/path/test.md",
                1024L, "text/markdown", "doc-hash",
                DocumentStatus.CHUNKING, 0, 0,
                Optional.empty(), Optional.empty(), Map.of(), now, now
        );
        docRepository.save(doc);
        documentId = doc.id();
    }

    /** 创建测试用 DocumentChunk 实例。 */
    private DocumentChunk createTestChunk(int chunkIndex, String content) {
        return new DocumentChunk(
                UUID.randomUUID().toString(),
                documentId,
                knowledgeBaseId,
                content,
                Optional.empty(),
                chunkIndex,
                chunkIndex * 100,       // startOffset
                (chunkIndex + 1) * 100, // endOffset
                content.length() / 4,   // tokenCount 估算
                "sha256-" + chunkIndex,
                List.of("第一章", "第" + (chunkIndex + 1) + "节"),
                1,
                Map.of("index", String.valueOf(chunkIndex))
        );
    }

    @Test
    void saveAll_findByDocumentId_roundTrip() {
        var chunks = List.of(
                createTestChunk(0, "第一个分块的内容"),
                createTestChunk(1, "第二个分块的内容"),
                createTestChunk(2, "第三个分块的内容")
        );

        chunkRepository.saveAll(chunks);
        var found = chunkRepository.findByDocumentId(documentId);

        assertThat(found).hasSize(3);
        // 验证第一个分块的字段
        var first = found.get(0);
        assertThat(first.documentId()).isEqualTo(documentId);
        assertThat(first.knowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(first.content()).isEqualTo("第一个分块的内容");
        assertThat(first.contextPrefix()).isEmpty();
        assertThat(first.chunkIndex()).isZero();
        assertThat(first.startOffset()).isZero();
        assertThat(first.endOffset()).isEqualTo(100);
        assertThat(first.contentHash()).isEqualTo("sha256-0");
        assertThat(first.headingHierarchy()).containsExactly("第一章", "第1节");
        assertThat(first.pageNumber()).isEqualTo(1);
        assertThat(first.metadata()).containsEntry("index", "0");
    }

    @Test
    void saveAll_带contextPrefix() {
        var chunk = new DocumentChunk(
                UUID.randomUUID().toString(), documentId, knowledgeBaseId,
                "分块内容", Optional.of("上下文前缀"),
                0, 0, 50, 10, "hash",
                List.of("标题"), 1, Map.of()
        );

        chunkRepository.saveAll(List.of(chunk));
        var found = chunkRepository.findByDocumentId(documentId);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).contextPrefix()).hasValue("上下文前缀");
    }

    @Test
    void findByDocumentId_按chunk_index升序排列() {
        // 故意乱序插入
        var chunk2 = createTestChunk(2, "第三个");
        var chunk0 = createTestChunk(0, "第一个");
        var chunk1 = createTestChunk(1, "第二个");
        chunkRepository.saveAll(List.of(chunk2, chunk0, chunk1));

        var found = chunkRepository.findByDocumentId(documentId);
        assertThat(found).hasSize(3);
        assertThat(found.get(0).chunkIndex()).isZero();
        assertThat(found.get(1).chunkIndex()).isEqualTo(1);
        assertThat(found.get(2).chunkIndex()).isEqualTo(2);
    }

    @Test
    void findByDocumentId_不存在的文档_返回空列表() {
        assertThat(chunkRepository.findByDocumentId("non-existent")).isEmpty();
    }

    @Test
    void countByDocumentId_返回正确数量() {
        var chunks = List.of(
                createTestChunk(0, "内容1"),
                createTestChunk(1, "内容2"),
                createTestChunk(2, "内容3")
        );
        chunkRepository.saveAll(chunks);

        assertThat(chunkRepository.countByDocumentId(documentId)).isEqualTo(3);
    }

    @Test
    void countByDocumentId_无分块_返回零() {
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
    }

    @Test
    void deleteByDocumentId_删除所有分块() {
        var chunks = List.of(
                createTestChunk(0, "内容1"),
                createTestChunk(1, "内容2")
        );
        chunkRepository.saveAll(chunks);
        assertThat(chunkRepository.countByDocumentId(documentId)).isEqualTo(2);

        chunkRepository.deleteByDocumentId(documentId);

        assertThat(chunkRepository.findByDocumentId(documentId)).isEmpty();
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
    }

    @Test
    void deleteByDocumentId_不影响其他文档的分块() {
        // 创建第二个文档
        var now = Instant.now();
        var doc2 = new Document(
                UUID.randomUUID().toString(), knowledgeBaseId, "other.md", "/path/other.md",
                512L, "text/markdown", "hash2",
                DocumentStatus.CHUNKING, 0, 0,
                Optional.empty(), Optional.empty(), Map.of(), now, now
        );
        docRepository.save(doc2);

        // 为两个文档各创建分块
        chunkRepository.saveAll(List.of(createTestChunk(0, "文档1分块")));
        var otherChunk = new DocumentChunk(
                UUID.randomUUID().toString(), doc2.id(), knowledgeBaseId,
                "文档2分块", Optional.empty(), 0, 0, 50, 5, "hash",
                List.of(), 1, Map.of()
        );
        chunkRepository.saveAll(List.of(otherChunk));

        // 只删除第一个文档的分块
        chunkRepository.deleteByDocumentId(documentId);

        assertThat(chunkRepository.findByDocumentId(documentId)).isEmpty();
        assertThat(chunkRepository.findByDocumentId(doc2.id())).hasSize(1);
    }

    @Test
    void saveAll_空列表_不抛异常() {
        chunkRepository.saveAll(List.of());
        assertThat(chunkRepository.countByDocumentId(documentId)).isZero();
    }

    @Test
    void saveAll_headingHierarchy的JSON序列化反序列化() {
        var chunk = new DocumentChunk(
                UUID.randomUUID().toString(), documentId, knowledgeBaseId,
                "内容", Optional.empty(), 0, 0, 50, 5,
                "hash", List.of("第一章", "第一节", "第一小节"),
                1, Map.of()
        );
        chunkRepository.saveAll(List.of(chunk));

        var found = chunkRepository.findByDocumentId(documentId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).headingHierarchy())
                .containsExactly("第一章", "第一节", "第一小节");
    }

    @Test
    void saveAll_metadata的JSON序列化反序列化() {
        var chunk = new DocumentChunk(
                UUID.randomUUID().toString(), documentId, knowledgeBaseId,
                "内容", Optional.empty(), 0, 0, 50, 5,
                "hash", List.of(), 1,
                Map.of("source", "markdown", "language", "zh")
        );
        chunkRepository.saveAll(List.of(chunk));

        var found = chunkRepository.findByDocumentId(documentId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).metadata())
                .containsEntry("source", "markdown")
                .containsEntry("language", "zh");
    }
}
