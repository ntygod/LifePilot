package com.lifepilot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.exception.DocumentNotFoundException;
import com.lifepilot.knowledge.exception.KnowledgeBaseNotFoundException;
import com.lifepilot.knowledge.index.FtsIndexer;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KnowledgeBaseManager 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证知识库管理服务的
 * CRUD 操作、级联删除和异常处理。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableConfigurationProperties(ToolConfigProperties.class)
class KnowledgeBaseManagerTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-kb-mgr-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-kb-mgr-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    KnowledgeBaseRepository kbRepository;
    DocumentRepository docRepository;
    DocumentChunkRepository chunkRepository;
    KnowledgeBaseManager manager;

    @BeforeEach
    void setUp() {
        kbRepository = new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
        docRepository = new DocumentRepository(jdbcTemplate, objectMapper);
        chunkRepository = new DocumentChunkRepository(jdbcTemplate, objectMapper);
        manager = new KnowledgeBaseManager(kbRepository, docRepository, chunkRepository,
                null, new FtsIndexer(jdbcTemplate));
        // 清理测试数据
        jdbcTemplate.execute("DELETE FROM document_chunks");
        jdbcTemplate.execute("DELETE FROM documents");
        jdbcTemplate.execute("DELETE FROM knowledge_bases");
    }

    @Test
    void createKnowledgeBase_getKnowledgeBase_roundTrip() {
        var kb = manager.createKnowledgeBase("测试知识库", "测试描述", "text-embedding-3-small",
                null, null, null, null);

        var found = manager.getKnowledgeBase(kb.id());

        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.id()).isEqualTo(kb.id());
        assertThat(result.name()).isEqualTo("测试知识库");
        assertThat(result.description()).isEqualTo("测试描述");
        assertThat(result.embeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(result.chunkingStrategy()).isEqualTo("smart");
        assertThat(result.documentCount()).isZero();
        assertThat(result.totalChunks()).isZero();
    }

    @Test
    void createKnowledgeBase_出现在listKnowledgeBases中() {
        var kb = manager.createKnowledgeBase("列表测试", "描述", "model",
                null, null, null, null);

        var list = manager.listKnowledgeBases();

        assertThat(list).anyMatch(item -> item.id().equals(kb.id()));
    }

    @Test
    void updateKnowledgeBase_更新名称和描述() {
        var kb = manager.createKnowledgeBase("原始名称", "原始描述", "model",
                null, null, null, null);

        var updated = manager.updateKnowledgeBase(kb.id(), "新名称", "新描述",
                null, null, null, null, null);

        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.description()).isEqualTo("新描述");
        // 验证持久化
        var found = manager.getKnowledgeBase(kb.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("新名称");
        assertThat(found.get().description()).isEqualTo("新描述");
    }

    @Test
    void updateKnowledgeBase_null参数不更新对应字段() {
        var kb = manager.createKnowledgeBase("保持名称", "保持描述", "model",
                null, null, null, null);

        // null name 不更新名称
        var updated1 = manager.updateKnowledgeBase(kb.id(), null, "新描述",
                null, null, null, null, null);
        assertThat(updated1.name()).isEqualTo("保持名称");
        assertThat(updated1.description()).isEqualTo("新描述");

        // null description 不更新描述
        var updated2 = manager.updateKnowledgeBase(kb.id(), "新名称", null,
                null, null, null, null, null);
        assertThat(updated2.name()).isEqualTo("新名称");
        assertThat(updated2.description()).isEqualTo("新描述");
    }

    @Test
    void updateKnowledgeBase_不存在的id_抛出KnowledgeBaseNotFoundException() {
        assertThatThrownBy(() -> manager.updateKnowledgeBase("non-existent-id", "名称", "描述",
                null, null, null, null, null))
                .isInstanceOf(KnowledgeBaseNotFoundException.class);
    }

    @Test
    void deleteKnowledgeBase_级联删除文档和分块() {
        // 创建知识库
        var kb = manager.createKnowledgeBase("待删除知识库", "描述", "model",
                null, null, null, null);

        // 手动插入文档
        var docId = UUID.randomUUID().toString();
        var doc = new Document(
                docId, kb.id(), "test.md", "/path/test.md", 1024,
                "text/markdown", "hash", DocumentStatus.READY, 2, 0,
                null, null, Map.of(),
                Instant.now(), Instant.now()
        );
        docRepository.save(doc);

        // 手动插入分块
        var chunk1 = new DocumentChunk(
                UUID.randomUUID().toString(), docId, kb.id(),
                "content1", null, 0, 0, 100, 10, "hash1",
                List.of(), 0, Map.of()
        );
        var chunk2 = new DocumentChunk(
                UUID.randomUUID().toString(), docId, kb.id(),
                "content2", null, 1, 100, 200, 10, "hash2",
                List.of(), 0, Map.of()
        );
        chunkRepository.saveAll(List.of(chunk1, chunk2));

        // 验证数据已插入
        assertThat(docRepository.findById(docId)).isPresent();
        assertThat(chunkRepository.countByDocumentId(docId)).isEqualTo(2);

        // 删除知识库
        manager.deleteKnowledgeBase(kb.id());

        // 验证级联删除
        assertThat(manager.getKnowledgeBase(kb.id())).isEmpty();
        assertThat(docRepository.findById(docId)).isEmpty();
        assertThat(chunkRepository.countByDocumentId(docId)).isZero();
    }

    @Test
    void listDocuments_返回指定知识库的文档() {
        var kb = manager.createKnowledgeBase("文档列表测试", "描述", "model",
                null, null, null, null);

        var doc1 = new Document(
                UUID.randomUUID().toString(), kb.id(), "doc1.md", "/path/doc1.md", 512,
                "text/markdown", "hash1", DocumentStatus.READY, 1, 0,
                null, null, Map.of(),
                Instant.now(), Instant.now()
        );
        var doc2 = new Document(
                UUID.randomUUID().toString(), kb.id(), "doc2.md", "/path/doc2.md", 1024,
                "text/markdown", "hash2", DocumentStatus.READY, 2, 0,
                null, null, Map.of(),
                Instant.now(), Instant.now()
        );
        docRepository.save(doc1);
        docRepository.save(doc2);

        var docs = manager.listDocuments(kb.id());

        assertThat(docs).hasSize(2);
        assertThat(docs).extracting(Document::id).containsExactlyInAnyOrder(doc1.id(), doc2.id());
    }

    @Test
    void removeDocument_删除文档和关联分块() {
        var kb = manager.createKnowledgeBase("删除文档测试", "描述", "model",
                null, null, null, null);

        var docId = UUID.randomUUID().toString();
        var doc = new Document(
                docId, kb.id(), "test.md", "/path/test.md", 1024,
                "text/markdown", "hash", DocumentStatus.READY, 1, 0,
                null, null, Map.of(),
                Instant.now(), Instant.now()
        );
        docRepository.save(doc);

        var chunk = new DocumentChunk(
                UUID.randomUUID().toString(), docId, kb.id(),
                "content", null, 0, 0, 100, 10, "hash",
                List.of(), 0, Map.of()
        );
        chunkRepository.saveAll(List.of(chunk));

        // 验证数据已插入
        assertThat(docRepository.findById(docId)).isPresent();
        assertThat(chunkRepository.countByDocumentId(docId)).isEqualTo(1);

        // 删除文档
        manager.removeDocument(docId);

        // 验证文档和分块都已删除
        assertThat(docRepository.findById(docId)).isEmpty();
        assertThat(chunkRepository.countByDocumentId(docId)).isZero();
    }

    @Test
    void removeDocument_不存在的id_抛出DocumentNotFoundException() {
        assertThatThrownBy(() -> manager.removeDocument("non-existent-id"))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
