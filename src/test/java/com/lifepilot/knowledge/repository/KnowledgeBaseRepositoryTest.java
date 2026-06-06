package com.lifepilot.knowledge.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.model.KnowledgeBase;
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

/**
 * KnowledgeBaseRepository 集成测试。
 *
 * <p>使用 @SpringBootTest + 内存 SQLite 验证 save/findById/findAll/deleteById
 * round-trip 以及 findAll 按 created_at 降序排列。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
@EnableConfigurationProperties(ToolConfigProperties.class)
class KnowledgeBaseRepositoryTest {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-kb-repo-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-kb-repo-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.store.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private KnowledgeBaseRepository repository;

    @BeforeEach
    void setUp() {
        repository = new KnowledgeBaseRepository(jdbcTemplate, objectMapper);
        // 清理测试数据
        jdbcTemplate.execute("DELETE FROM document_chunks");
        jdbcTemplate.execute("DELETE FROM documents");
        jdbcTemplate.execute("DELETE FROM knowledge_bases");
    }

    @Test
    void save_findById_roundTrip() {
        var kb = KnowledgeBase.create("测试知识库", "测试描述", "text-embedding-3-small",
                null, null, null, null);

        repository.save(kb);
        var found = repository.findById(kb.id());

        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.id()).isEqualTo(kb.id());
        assertThat(result.name()).isEqualTo("测试知识库");
        assertThat(result.description()).isEqualTo("测试描述");
        assertThat(result.embeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(result.rerankerModel()).isNull();
        assertThat(result.chunkingStrategy()).isEqualTo("smart");
        assertThat(result.chunkingConfig()).isEmpty();
        assertThat(result.documentCount()).isZero();
        assertThat(result.totalChunks()).isZero();
        assertThat(result.createdAt()).isEqualTo(kb.createdAt());
        assertThat(result.updatedAt()).isEqualTo(kb.updatedAt());
    }

    @Test
    void save_空EmbeddingModel_持久化为默认值且读出为null() {
        var kb = KnowledgeBase.create("默认模型知识库", "描述", null,
                null, null, null, null);

        repository.save(kb);

        var found = repository.findById(kb.id());
        assertThat(found).isPresent();
        assertThat(found.get().embeddingModel()).isNull();

        String rawValue = jdbcTemplate.queryForObject(
                "SELECT embedding_model FROM knowledge_bases WHERE id = ?",
                String.class,
                kb.id()
        );
        assertThat(rawValue).isEqualTo("default");
    }

    @Test
    void save_upsert_更新已有记录() {
        var kb = KnowledgeBase.create("原始名称", "原始描述", "model-v1",
                null, null, null, null);
        repository.save(kb);

        // 使用相同 id 保存更新后的记录
        var updated = new KnowledgeBase(
                kb.id(), "更新名称", "更新描述", "model-v2",
                "reranker-v1", "fixed-size",
                Map.of("maxChunkSize", 2048),
                5, 100, List.of(), kb.createdAt(), Instant.now()
        );
        repository.save(updated);

        var found = repository.findById(kb.id());
        assertThat(found).isPresent();
        var result = found.get();
        assertThat(result.name()).isEqualTo("更新名称");
        assertThat(result.description()).isEqualTo("更新描述");
        assertThat(result.embeddingModel()).isEqualTo("model-v2");
        assertThat(result.rerankerModel()).isEqualTo("reranker-v1");
        assertThat(result.chunkingStrategy()).isEqualTo("fixed-size");
        assertThat(result.documentCount()).isEqualTo(5);
        assertThat(result.totalChunks()).isEqualTo(100);

        // upsert 不应产生重复记录
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void findById_不存在的id_返回empty() {
        var found = repository.findById("non-existent-id");
        assertThat(found).isEmpty();
    }

    @Test
    void findAll_按created_at降序排列() throws InterruptedException {
        var now = Instant.now();
        var kb1 = new KnowledgeBase(
                UUID.randomUUID().toString(), "知识库1", "", "model",
                null, "smart", Map.of(), 0, 0,
                List.of(), now.minusSeconds(30), now.minusSeconds(30)
        );
        var kb2 = new KnowledgeBase(
                UUID.randomUUID().toString(), "知识库2", "", "model",
                null, "smart", Map.of(), 0, 0,
                List.of(), now.minusSeconds(20), now.minusSeconds(20)
        );
        var kb3 = new KnowledgeBase(
                UUID.randomUUID().toString(), "知识库3", "", "model",
                null, "smart", Map.of(), 0, 0,
                List.of(), now.minusSeconds(10), now.minusSeconds(10)
        );

        // 故意乱序插入
        repository.save(kb2);
        repository.save(kb1);
        repository.save(kb3);

        var all = repository.findAll();
        assertThat(all).hasSize(3);
        // 按 created_at 降序：kb3 > kb2 > kb1
        assertThat(all.get(0).name()).isEqualTo("知识库3");
        assertThat(all.get(1).name()).isEqualTo("知识库2");
        assertThat(all.get(2).name()).isEqualTo("知识库1");
    }

    @Test
    void findAll_空表_返回空列表() {
        var all = repository.findAll();
        assertThat(all).isEmpty();
    }

    @Test
    void deleteById_删除已有记录() {
        var kb = KnowledgeBase.create("待删除", "描述", "model",
                null, null, null, null);
        repository.save(kb);
        assertThat(repository.findById(kb.id())).isPresent();

        repository.deleteById(kb.id());
        assertThat(repository.findById(kb.id())).isEmpty();
    }

    @Test
    void deleteById_不存在的id_不抛异常() {
        // 删除不存在的记录不应抛异常
        repository.deleteById("non-existent-id");
    }

    @Test
    void updateDocumentCount_更新文档数和分块数() {
        var kb = KnowledgeBase.create("知识库", "描述", "model",
                null, null, null, null);
        repository.save(kb);

        repository.updateDocumentCount(kb.id(), 10, 500);

        var found = repository.findById(kb.id());
        assertThat(found).isPresent();
        assertThat(found.get().documentCount()).isEqualTo(10);
        assertThat(found.get().totalChunks()).isEqualTo(500);
        // updated_at 应该被更新
        assertThat(found.get().updatedAt()).isAfterOrEqualTo(kb.updatedAt());
    }

    @Test
    void save_带chunkingConfig的JSON序列化反序列化() {
        var config = Map.<String, Object>of(
                "maxChunkSize", 2048,
                "overlapSize", 128,
                "respectSentences", true
        );
        var kb = new KnowledgeBase(
                UUID.randomUUID().toString(), "JSON测试", "描述", "model",
                null, "fixed-size", config,
                0, 0, List.of(), Instant.now(), Instant.now()
        );
        repository.save(kb);

        var found = repository.findById(kb.id());
        assertThat(found).isPresent();
        var resultConfig = found.get().chunkingConfig();
        assertThat(resultConfig).containsEntry("maxChunkSize", 2048);
        assertThat(resultConfig).containsEntry("overlapSize", 128);
        assertThat(resultConfig).containsEntry("respectSentences", true);
    }
}
