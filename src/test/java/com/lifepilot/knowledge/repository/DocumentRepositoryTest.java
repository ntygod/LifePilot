package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证 save/findById/findByKnowledgeBaseId/
 * deleteById/updateStatus/updateContentHash/updateChunkCount/updateLastProcessedStage
 * round-trip。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-doc-repo-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-doc-repo-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private DocumentRepository documentRepository;
    private KnowledgeBaseRepository kbRepository;

    /** 测试用知识库 id，每个测试方法前创建。 */
    private String knowledgeBaseId;

    @BeforeEach
    void setUp() {
        documentRepository = new DocumentRepository(jdbcTemplate, objectMapper);
        kbRepository = new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
        // 清理测试数据
        jdbcTemplate.execute("DELETE FROM document_chunks");
        jdbcTemplate.execute("DELETE FROM documents");
        jdbcTemplate.execute("DELETE FROM knowledge_bases");
        // 创建前置知识库
        var kb = KnowledgeBase.create("测试知识库", "描述", "model",
                null, null, null, null);
        kbRepository.save(kb);
        knowledgeBaseId = kb.id();
    }

    /** 创建测试用 Document 实例。 */
    private Document createTestDocument(String id, String fileName) {
        var now = Instant.now();
        return new Document(
                id, knowledgeBaseId, fileName, "/path/to/" + fileName,
                1024L, "text/markdown", "sha256-hash-" + id,
                DocumentStatus.UPLOADING, 0, 0,
                null, null,
                Map.of("key", "value"), now, now
        );
    }

    @Test
    void save_findById_roundTrip() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "test.md");

        documentRepository.save(doc);
        var found = documentRepository.findById(doc.id());

        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.id()).isEqualTo(doc.id());
        assertThat(result.knowledgeBaseId()).isEqualTo(knowledgeBaseId);
        assertThat(result.fileName()).isEqualTo("test.md");
        assertThat(result.filePath()).isEqualTo("/path/to/test.md");
        assertThat(result.fileSize()).isEqualTo(1024L);
        assertThat(result.mimeType()).isEqualTo("text/markdown");
        assertThat(result.contentHash()).isEqualTo("sha256-hash-" + doc.id());
        assertThat(result.status()).isEqualTo(DocumentStatus.UPLOADING);
        assertThat(result.chunkCount()).isZero();
        assertThat(result.entityCount()).isZero();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.lastProcessedStage()).isNull();
        assertThat(result.metadata()).containsEntry("key", "value");
        assertThat(result.createdAt()).isEqualTo(doc.createdAt());
        assertThat(result.updatedAt()).isEqualTo(doc.updatedAt());
    }

    @Test
    void save_带errorMessage和lastProcessedStage() {
        var now = Instant.now();
        var doc = new Document(
                UUID.randomUUID().toString(), knowledgeBaseId, "error.md", "/path/error.md",
                512L, "text/markdown", "hash",
                DocumentStatus.ERROR, 0, 0,
                "解析失败", "PARSING",
                Map.of(), now, now
        );

        documentRepository.save(doc);
        var found = documentRepository.findById(doc.id());

        assertThat(found).isPresent();
        assertThat(found.get().errorMessage()).isEqualTo("解析失败");
        assertThat(found.get().lastProcessedStage()).isEqualTo("PARSING");
        assertThat(found.get().status()).isEqualTo(DocumentStatus.ERROR);
    }

    @Test
    void findById_不存在的id_返回empty() {
        assertThat(documentRepository.findById("non-existent")).isEmpty();
    }

    @Test
    void findByKnowledgeBaseId_返回该知识库下所有文档() {
        var doc1 = createTestDocument(UUID.randomUUID().toString(), "doc1.md");
        var doc2 = createTestDocument(UUID.randomUUID().toString(), "doc2.md");
        documentRepository.save(doc1);
        documentRepository.save(doc2);

        var docs = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        assertThat(docs).hasSize(2);
        assertThat(docs).extracting(Document::fileName)
                .containsExactlyInAnyOrder("doc1.md", "doc2.md");
    }

    @Test
    void findByKnowledgeBaseId_不同知识库的文档不混淆() {
        // 创建第二个知识库
        var kb2 = KnowledgeBase.create("另一个知识库", "描述", "model",
                null, null, null, null);
        kbRepository.save(kb2);

        var doc1 = createTestDocument(UUID.randomUUID().toString(), "doc1.md");
        var now = Instant.now();
        var doc2 = new Document(
                UUID.randomUUID().toString(), kb2.id(), "doc2.md", "/path/doc2.md",
                512L, "text/plain", "hash2",
                DocumentStatus.READY, 0, 0,
                null, null, Map.of(), now, now
        );
        documentRepository.save(doc1);
        documentRepository.save(doc2);

        var docs = documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).fileName()).isEqualTo("doc1.md");
    }

    @Test
    void deleteById_删除文档() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "to-delete.md");
        documentRepository.save(doc);
        assertThat(documentRepository.findById(doc.id())).isPresent();

        documentRepository.deleteById(doc.id());
        assertThat(documentRepository.findById(doc.id())).isEmpty();
    }

    @Test
    void updateStatus_更新状态和错误消息() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "status.md");
        documentRepository.save(doc);

        documentRepository.updateStatus(doc.id(), DocumentStatus.ERROR, "解析超时");

        var found = documentRepository.findById(doc.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(DocumentStatus.ERROR);
        assertThat(found.get().errorMessage()).isEqualTo("解析超时");
        assertThat(found.get().updatedAt()).isAfterOrEqualTo(doc.updatedAt());
    }

    @Test
    void updateStatus_清除错误消息() {
        var now = Instant.now();
        var doc = new Document(
                UUID.randomUUID().toString(), knowledgeBaseId, "recover.md", "/path/recover.md",
                512L, "text/markdown", "hash",
                DocumentStatus.ERROR, 0, 0,
                "之前的错误", null, Map.of(), now, now
        );
        documentRepository.save(doc);

        documentRepository.updateStatus(doc.id(), DocumentStatus.PARSING, null);

        var found = documentRepository.findById(doc.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(DocumentStatus.PARSING);
        assertThat(found.get().errorMessage()).isNull();
    }

    @Test
    void updateContentHash_更新内容哈希() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "hash.md");
        documentRepository.save(doc);

        documentRepository.updateContentHash(doc.id(), "new-sha256-hash");

        var found = documentRepository.findById(doc.id());
        assertThat(found).isPresent();
        assertThat(found.get().contentHash()).isEqualTo("new-sha256-hash");
        assertThat(found.get().updatedAt()).isAfterOrEqualTo(doc.updatedAt());
    }

    @Test
    void updateChunkCount_更新分块数() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "chunks.md");
        documentRepository.save(doc);

        documentRepository.updateChunkCount(doc.id(), 42);

        var found = documentRepository.findById(doc.id());
        assertThat(found).isPresent();
        assertThat(found.get().chunkCount()).isEqualTo(42);
        assertThat(found.get().updatedAt()).isAfterOrEqualTo(doc.updatedAt());
    }

    @Test
    void updateLastProcessedStage_更新最后处理阶段() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "stage.md");
        documentRepository.save(doc);

        documentRepository.updateLastProcessedStage(doc.id(), "CHUNKING");

        var found = documentRepository.findById(doc.id());
        assertThat(found).isPresent();
        assertThat(found.get().lastProcessedStage()).isEqualTo("CHUNKING");
        assertThat(found.get().updatedAt()).isAfterOrEqualTo(doc.updatedAt());
    }

    @Test
    void save_upsert_更新已有文档() {
        var doc = createTestDocument(UUID.randomUUID().toString(), "original.md");
        documentRepository.save(doc);

        var updated = new Document(
                doc.id(), knowledgeBaseId, "updated.md", "/path/updated.md",
                2048L, "text/plain", "new-hash",
                DocumentStatus.READY, 10, 5,
                null, "INDEXING",
                Map.of("updated", "true"), doc.createdAt(), Instant.now()
        );
        documentRepository.save(updated);

        var found = documentRepository.findById(doc.id());
        assertThat(found).isPresent();
        assertThat(found.get().fileName()).isEqualTo("updated.md");
        assertThat(found.get().fileSize()).isEqualTo(2048L);
        assertThat(found.get().status()).isEqualTo(DocumentStatus.READY);
        assertThat(found.get().chunkCount()).isEqualTo(10);

        // upsert 不应产生重复
        assertThat(documentRepository.findByKnowledgeBaseId(knowledgeBaseId)).hasSize(1);
    }
}
