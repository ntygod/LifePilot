package com.lifepilot.datastore;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.*;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.knowledge.model.Document;
import com.lifepilot.knowledge.repository.DocumentRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * DataStoreManager 集成测试 — 使用内存 SQLite 验证完整 CRUD、聚合和校验逻辑。
 *
 * <p>手动组装 Bean（不启动 Spring Context），直接操作内存 SQLite 数据库。
 * 每个测试方法共享同一个数据库实例，通过 {@code @TestMethodOrder} 控制执行顺序。</p>
 *
 * <p>文档优先模型下，文档直接存入 knowledge 模块的 documents 表，
 * 集合通过 source_datastore_id 关联文档。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataStoreManager集成测试 {

    private static JdbcTemplate jdbcTemplate;
    private static DataStoreManager manager;
    private static DataStoreProperties properties;
    private static DocumentRepository knowledgeDocRepository;
    private static SingleConnectionDataSource dataSource;

    // 跨测试共享的 ID
    private static String docCollectionId;
    private static String timeSeriesCollectionId;
    private static String documentId;

    @BeforeAll
    static void setUp() {
        // 创建内存 SQLite 数据源
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);

        var sqliteDs = new SQLiteDataSource(config);
        sqliteDs.setUrl("jdbc:sqlite::memory:");

        // 使用 SingleConnectionDataSource 确保所有操作共享同一个内存数据库连接
        dataSource = new SingleConnectionDataSource(sqliteDs.getUrl(), false);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 创建 ds_collections 表
        jdbcTemplate.execute("""
                CREATE TABLE ds_collections (
                    id              TEXT PRIMARY KEY,
                    name            TEXT NOT NULL UNIQUE,
                    description     TEXT,
                    time_series     INTEGER NOT NULL DEFAULT 0,
                    field_hints_json TEXT,
                    default_knowledge_base_id TEXT,
                    created_by      TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);

        // 创建 knowledge 模块的 documents 表（完整列名，匹配 DocumentRepository 查询）
        jdbcTemplate.execute("""
                CREATE TABLE documents (
                    id                   TEXT PRIMARY KEY,
                    knowledge_base_id    TEXT,
                    file_name            TEXT NOT NULL DEFAULT '',
                    file_path            TEXT NOT NULL DEFAULT '',
                    file_size            INTEGER NOT NULL DEFAULT 0,
                    mime_type            TEXT NOT NULL DEFAULT 'text/plain',
                    content_hash         TEXT NOT NULL DEFAULT '',
                    status               TEXT NOT NULL DEFAULT 'READY',
                    chunk_count          INTEGER NOT NULL DEFAULT 0,
                    entity_count         INTEGER NOT NULL DEFAULT 0,
                    error_message        TEXT,
                    last_processed_stage TEXT,
                    metadata_json        TEXT NOT NULL DEFAULT '{}',
                    created_at           TEXT NOT NULL,
                    updated_at           TEXT NOT NULL,
                    source_type          TEXT NOT NULL DEFAULT 'FILE',
                    source_key           TEXT NOT NULL DEFAULT '',
                    source_datastore_id  TEXT,
                    source_collection_id TEXT,
                    source_ref_json      TEXT NOT NULL DEFAULT '{}',
                    content              TEXT,
                    recorded_at          TEXT
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX idx_documents_source_datastore ON documents(source_datastore_id)");
        jdbcTemplate.execute(
                "CREATE INDEX idx_documents_recorded_at ON documents(source_datastore_id, recorded_at)");

        // 创建 knowledge_bases 表（满足外键约束的最小结构）
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);

        // 组装 Bean
        properties = new DataStoreProperties();
        properties.setMaxCollections(5);
        properties.setMaxDocumentsPerCollection(10);
        properties.setMaxDocumentSizeBytes(1024);

        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.findAndRegisterModules();
        var collectionRepo = new CollectionRepository(jdbcTemplate);
        knowledgeDocRepository = new DocumentRepository(jdbcTemplate, objectMapper);
        var queryEngine = new QueryEngine(properties);
        var aggregationEngine = new AggregationEngine();

        manager = new DataStoreManager(
                collectionRepo, queryEngine,
                aggregationEngine, properties,
                objectMapper,
                null, null, knowledgeDocRepository, null);
    }

    // ==================== 集合 CRUD ====================

    @Test
    @Order(1)
    void 创建普通集合_成功() {
        var fieldHints = List.of(
                new FieldHint("title", "TEXT", "标题"),
                new FieldHint("priority", "NUMBER", "优先级")
        );
        var collection = manager.createCollection("todos", false,
                fieldHints, "待办事项", "test-user");

        assertThat(collection.id()).isNotBlank();
        assertThat(collection.name()).isEqualTo("todos");
        assertThat(collection.timeSeries()).isFalse();
        assertThat(collection.description()).isEqualTo("待办事项");
        assertThat(collection.createdBy()).isEqualTo("test-user");
        assertThat(collection.fieldHintsJson()).contains("title");

        docCollectionId = collection.id();
    }

    @Test
    @Order(2)
    void 创建普通集合无提示_成功() {
        var collection = manager.createCollection("notes", false,
                null, "笔记", null);

        assertThat(collection.timeSeries()).isFalse();
    }

    @Test
    @Order(3)
    void 创建时序集合_成功() {
        var fieldHints = List.of(
                new FieldHint("weight", "NUMBER", "体重")
        );
        var collection = manager.createCollection("health", true,
                fieldHints, "健康指标", null);

        assertThat(collection.timeSeries()).isTrue();
        timeSeriesCollectionId = collection.id();
    }

    @Test
    @Order(4)
    void 创建重名集合_抛出异常() {
        assertThatThrownBy(() ->
                manager.createCollection("todos", false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合名称已存在");
    }

    @Test
    @Order(5)
    void 集合数量达上限_抛出异常() {
        // 已有 3 个集合，上限 5，再创建 2 个填满
        manager.createCollection("col4", false, null, null, null);
        manager.createCollection("col5", false, null, null, null);

        assertThatThrownBy(() ->
                manager.createCollection("col6", false, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("集合数量已达上限");
    }

    @Test
    @Order(6)
    void 查询所有集合() {
        var collections = manager.listCollections();
        assertThat(collections).hasSize(5);
    }

    @Test
    @Order(7)
    void 按时序标记查询集合() {
        var nonTimeSeries = manager.listCollections(false);
        assertThat(nonTimeSeries).hasSizeGreaterThanOrEqualTo(3);

        var timeSeries = manager.listCollections(true);
        assertThat(timeSeries).hasSize(1);
    }

    @Test
    @Order(8)
    void 按名称查找集合() {
        var found = manager.findCollection("todos");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("todos");

        var notFound = manager.findCollection("nonexistent");
        assertThat(notFound).isEmpty();
    }

    @Test
    @Order(9)
    void 更新集合描述() {
        boolean updated = manager.updateCollection(docCollectionId, "更新后的描述", null);
        assertThat(updated).isTrue();

        var collection = manager.findCollection("todos").orElseThrow();
        assertThat(collection.description()).isEqualTo("更新后的描述");
    }

    // ==================== 文档 CRUD ====================

    @Test
    @Order(10)
    void 添加文档_普通集合_成功() {
        documentId = manager.addDocument(docCollectionId,
                "买牛奶，优先级1", "{\"title\": \"买牛奶\", \"priority\": 1}", null);

        assertThat(documentId).isNotBlank();
    }

    @Test
    @Order(11)
    void 添加文档_内容为空_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument(docCollectionId, "", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档内容不能为空");
    }

    @Test
    @Order(14)
    void 添加文档_超过大小限制_抛出异常() {
        // maxDocumentSizeBytes = 1024
        String largeContent = "x".repeat(2000);
        assertThatThrownBy(() ->
                manager.addDocument(docCollectionId, largeContent, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档大小超过限制");
    }

    @Test
    @Order(15)
    void 添加文档_集合不存在_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument("nonexistent-id", "test content", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合不存在");
    }

    @Test
    @Order(16)
    void 获取文档() {
        var doc = manager.getDocument(documentId);
        assertThat(doc).isPresent();
        assertThat(doc.get().content()).contains("买牛奶");

        var notFound = manager.getDocument("nonexistent");
        assertThat(notFound).isEmpty();
    }

    @Test
    @Order(17)
    void 更新文档_成功() {
        boolean updated = manager.updateDocument(documentId,
                "买酸奶，优先级2", "{\"title\": \"买酸奶\", \"priority\": 2}");
        assertThat(updated).isTrue();

        var doc = manager.getDocument(documentId).orElseThrow();
        assertThat(doc.content()).contains("买酸奶");
    }

    @Test
    @Order(25)
    void 条件查询_集合不存在_抛出异常() {
        var request = new QueryRequest("nonexistent", List.of(), null, null, 0, 10);
        assertThatThrownBy(() -> manager.queryDocuments(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合不存在");
    }

    // ==================== 时序集合 ====================

    @Test
    @Order(40)
    void 时序集合_添加文档_需要recordedAt() {
        assertThatThrownBy(() ->
                manager.addDocument(timeSeriesCollectionId, "content", "{\"weight\": 70.5}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时序集合文档必须包含 recordedAt");
    }

    @Test
    @Order(41)
    void 时序集合_recordedAt格式非法_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument(timeSeriesCollectionId, "content", "{\"weight\": 70.5}", "not-a-date"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordedAt 格式非法");
    }

    @Test
    @Order(42)
    void 时序集合_添加文档_成功() {
        manager.addDocument(timeSeriesCollectionId, "体重70.5", "{\"weight\": 70.5}", "2026-03-01T08:00:00");
        manager.addDocument(timeSeriesCollectionId, "体重71.0", "{\"weight\": 71.0}", "2026-03-02T08:00:00");
        manager.addDocument(timeSeriesCollectionId, "体重69.8", "{\"weight\": 69.8}", "2026-03-03T08:00:00");
        manager.addDocument(timeSeriesCollectionId, "体重72.0", "{\"weight\": 72.0}", "2026-03-15T08:00:00");
        manager.addDocument(timeSeriesCollectionId, "体重68.5", "{\"weight\": 68.5}", "2026-03-16T08:00:00");

        // 验证文档已添加
        var docs = manager.listDocuments(timeSeriesCollectionId);
        assertThat(docs).hasSize(5);
    }

    @Test
    @Order(43)
    void 时序聚合_SUM_无分组() {
        var request = new AggregationRequest(
                timeSeriesCollectionId, "weight", AggregateFunction.SUM,
                null, null, null);
        var results = manager.aggregate(request);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().timeBucket()).isEqualTo("total");
        // 70.5 + 71.0 + 69.8 + 72.0 + 68.5 = 351.8
        assertThat(results.getFirst().value()).isCloseTo(351.8, within(0.1));
    }

    @Test
    @Order(44)
    void 时序聚合_AVG_按天分组() {
        var request = new AggregationRequest(
                timeSeriesCollectionId, "weight", AggregateFunction.AVG,
                TimeGranularity.DAY, null, null);
        var results = manager.aggregate(request);

        // 5 天各一条记录
        assertThat(results).hasSize(5);
        assertThat(results.getFirst().timeBucket()).isEqualTo("2026-03-01");
    }

    @Test
    @Order(45)
    void 时序聚合_时间范围过滤() {
        var request = new AggregationRequest(
                timeSeriesCollectionId, "weight", AggregateFunction.AVG,
                null, "2026-03-01T00:00:00", "2026-03-04T00:00:00");
        var results = manager.aggregate(request);

        assertThat(results).hasSize(1);
        // (70.5 + 71.0 + 69.8) / 3 ≈ 70.43
        assertThat(results.getFirst().value()).isCloseTo(70.43, within(0.1));
    }

    @Test
    @Order(46)
    void 时序聚合_MAX() {
        var request = new AggregationRequest(
                timeSeriesCollectionId, "weight", AggregateFunction.MAX,
                null, null, null);
        var results = manager.aggregate(request);

        assertThat(results.getFirst().value()).isCloseTo(72.0, within(0.01));
    }

    @Test
    @Order(47)
    void 时序聚合_MIN() {
        var request = new AggregationRequest(
                timeSeriesCollectionId, "weight", AggregateFunction.MIN,
                null, null, null);
        var results = manager.aggregate(request);

        assertThat(results.getFirst().value()).isCloseTo(68.5, within(0.01));
    }

    @Test
    @Order(48)
    void 非时序集合_聚合_抛出异常() {
        var request = new AggregationRequest(
                docCollectionId, "priority", AggregateFunction.SUM,
                null, null, null);
        assertThatThrownBy(() -> manager.aggregate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时序聚合仅支持时序集合");
    }

    // ==================== 集合删除 ====================

    @Test
    @Order(60)
    void 删除集合_成功() {
        // 删除 col4 和 col5 腾出空间
        var col4 = manager.findCollection("col4").orElseThrow();
        var col5 = manager.findCollection("col5").orElseThrow();
        manager.deleteCollection(col4.id());
        manager.deleteCollection(col5.id());

        // 验证已删除
        assertThat(manager.findCollection("col4")).isEmpty();
        assertThat(manager.findCollection("col5")).isEmpty();
    }

    @Test
    @Order(61)
    void 删除集合_不存在_抛出异常() {
        assertThatThrownBy(() -> manager.deleteCollection("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合不存在");
    }

    // ==================== 文档数量限额 ====================

    @Test
    @Order(70)
    void 文档数量达上限_抛出异常() {
        // maxDocumentsPerCollection = 10
        var col = manager.createCollection("limit-test", false,
                null, null, null);

        for (int i = 0; i < 10; i++) {
            manager.addDocument(col.id(), "content " + i, "{\"index\": %d}".formatted(i), null);
        }

        assertThatThrownBy(() ->
                manager.addDocument(col.id(), "content overflow", "{\"index\": 10}", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("集合文档数量已达上限");
    }
}
