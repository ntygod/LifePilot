package com.lifepilot.knowledge.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.DocumentStatus;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.model.KnowledgeSearchScope;
import com.lifepilot.knowledge.repository.DocumentChunkRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.tool.config.ToolConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * FtsIndexer 集成测试。
 *
 * <p>验证 external-content FTS5 查询通过 rowid 回表 document_chunks，
 * 不再直接读取虚表的 chunk_id 列，避免因内容表列名不匹配导致查询失败。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableConfigurationProperties(ToolConfigProperties.class)
class FtsIndexer集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-fts-indexer-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-fts-indexer-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private KnowledgeBaseRepository kbRepository;
    private DocumentRepository documentRepository;
    private DocumentChunkRepository chunkRepository;
    private FtsIndexer ftsIndexer;

    private String knowledgeBaseId;
    private String documentId;

    @BeforeEach
    void setUp() {
        kbRepository = new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
        documentRepository = new DocumentRepository(jdbcTemplate, objectMapper);
        chunkRepository = new DocumentChunkRepository(jdbcTemplate, objectMapper);
        ftsIndexer = new FtsIndexer(jdbcTemplate);

        jdbcTemplate.execute("DELETE FROM document_chunks");
        jdbcTemplate.execute("DELETE FROM documents");
        jdbcTemplate.execute("DELETE FROM knowledge_bases");

        var kb = KnowledgeBase.create("检索测试知识库", "描述", "model",
                null, null, null, null);
        kbRepository.save(kb);
        knowledgeBaseId = kb.id();

        var now = Instant.now();
        var doc = new Document(
                UUID.randomUUID().toString(),
                knowledgeBaseId,
                "ARCHITECTURE.md",
                "/tmp/ARCHITECTURE.md",
                1024L,
                "text/markdown",
                "hash-architecture",
                DocumentStatus.READY,
                0,
                0,
                null,
                null,
                Map.of(),
                now,
                now,
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "FILE:test-architecture",
                "ds-dev",
                null,
                Map.of("datastoreId", "ds-dev")
        );
        documentRepository.save(doc);
        documentId = doc.id();
    }

    @Test
    void searchByScopes_领域过滤下可正常命中分块() {
        var targetChunk = new DocumentChunk(
                UUID.randomUUID().toString(),
                documentId,
                knowledgeBaseId,
                "6. 数据存储架构\n知微采用主库、向量库和数据目录三层结构。",
                Optional.empty(),
                0,
                0,
                40,
                12,
                "hash-1",
                List.of("第 6 章"),
                1,
                Map.of("section", "storage"),
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "ds-dev",
                null,
                Optional.empty(),
                0
        );
        var otherChunk = new DocumentChunk(
                UUID.randomUUID().toString(),
                documentId,
                knowledgeBaseId,
                "7. 部署方式\n系统支持本地运行和 Docker Compose。",
                Optional.empty(),
                1,
                41,
                80,
                10,
                "hash-2",
                List.of("第 7 章"),
                1,
                Map.of("section", "deploy"),
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "ds-other",
                null,
                Optional.empty(),
                0
        );
        chunkRepository.saveAll(List.of(targetChunk, otherChunk));

        List<DocumentSearchResult> results = ftsIndexer.searchByScopes(
                "数据存储架构",
                List.of(new KnowledgeSearchScope(knowledgeBaseId, "ds-dev")),
                5
        );

        assertThat(results).isNotEmpty();
        assertThat(results).extracting(DocumentSearchResult::chunkId).contains(targetChunk.id());
        assertThat(results).allMatch(result -> result.sourceDatastoreId().orElse("").equals("ds-dev"));
    }

    @Test
    void searchByScopes_冲突样本下应隔离不同Datastore结果() {
        String docAId = UUID.randomUUID().toString();
        String docBId = UUID.randomUUID().toString();
        var now = Instant.now();

        documentRepository.save(new Document(
                docAId,
                knowledgeBaseId,
                "设定-A.md",
                "/tmp/setting-a.md",
                512L,
                "text/markdown",
                "hash-a",
                DocumentStatus.READY,
                0,
                0,
                null,
                null,
                Map.of(),
                now,
                now,
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "FILE:setting-a",
                "ds-a",
                null,
                Map.of("datastoreId", "ds-a")
        ));
        documentRepository.save(new Document(
                docBId,
                knowledgeBaseId,
                "设定-B.md",
                "/tmp/setting-b.md",
                512L,
                "text/markdown",
                "hash-b",
                DocumentStatus.READY,
                0,
                0,
                null,
                null,
                Map.of(),
                now,
                now,
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "FILE:setting-b",
                "ds-b",
                null,
                Map.of("datastoreId", "ds-b")
        ));

        var chunkA = new DocumentChunk(
                UUID.randomUUID().toString(),
                docAId,
                knowledgeBaseId,
                "主角金手指设定：时间回溯，每次可回退三分钟。",
                Optional.empty(),
                0,
                0,
                28,
                12,
                "hash-a-1",
                List.of("人物设定"),
                1,
                Map.of("topic", "power"),
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "ds-a",
                null,
                Optional.empty(),
                0
        );
        var chunkB = new DocumentChunk(
                UUID.randomUUID().toString(),
                docBId,
                knowledgeBaseId,
                "主角金手指设定：情绪感知，能够读取附近人物的情绪波动。",
                Optional.empty(),
                0,
                0,
                31,
                13,
                "hash-b-1",
                List.of("人物设定"),
                1,
                Map.of("topic", "power"),
                com.lifepilot.knowledge.model.DocumentSourceType.FILE,
                "ds-b",
                null,
                Optional.empty(),
                0
        );
        chunkRepository.saveAll(List.of(chunkA, chunkB));

        List<DocumentSearchResult> results = ftsIndexer.searchByScopes(
                "主角金手指设定",
                List.of(new KnowledgeSearchScope(knowledgeBaseId, "ds-a")),
                10
        );

        assertThat(results).isNotEmpty();
        assertThat(results)
                .extracting(result -> result.sourceDatastoreId().orElse(null))
                .containsOnly("ds-a");
        assertThat(results)
                .extracting(DocumentSearchResult::content)
                .allMatch(content -> content.contains("时间回溯"));
    }
}
