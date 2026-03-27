package com.lifepilot.datastore;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.*;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
import com.lifepilot.datastore.validation.PropertyValidator;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * DataStoreManager 集成测试 — 使用内存 SQLite 验证完整 CRUD、FTS5、聚合和校验逻辑。
 *
 * <p>手动组装 Bean（不启动 Spring Context），直接操作内存 SQLite 数据库。
 * 每个测试方法共享同一个数据库实例，通过 {@code @TestMethodOrder} 控制执行顺序。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataStoreManager集成测试 {

    private static JdbcTemplate jdbcTemplate;
    private static DataStoreManager manager;
    private static DataStoreProperties properties;
    private static SingleConnectionDataSource dataSource;

    // 跨测试共享的 ID
    private static String docCollectionId;
    private static String noteCollectionId;
    private static String metricCollectionId;
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
        var dataSource = new SingleConnectionDataSource(sqliteDs.getUrl(), false);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 创建表结构
        jdbcTemplate.execute("""
                CREATE TABLE ds_collections (
                    id              TEXT PRIMARY KEY,
                    name            TEXT NOT NULL UNIQUE,
                    description     TEXT,
                    type            TEXT NOT NULL DEFAULT 'DOCUMENT',
                    properties_json TEXT,
                    projection_config_json TEXT NOT NULL DEFAULT '{}',
                    metadata_json   TEXT,
                    created_by      TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE ds_documents (
                    id            TEXT PRIMARY KEY,
                    collection_id TEXT NOT NULL REFERENCES ds_collections(id) ON DELETE CASCADE,
                    data_json     TEXT NOT NULL DEFAULT '{}',
                    recorded_at   TEXT,
                    created_at    TEXT NOT NULL,
                    updated_at    TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX idx_ds_documents_collection ON ds_documents(collection_id)");
        jdbcTemplate.execute(
                "CREATE INDEX idx_ds_documents_recorded_at ON ds_documents(collection_id, recorded_at)");
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE ds_documents_fts USING fts5(
                    document_id, content,
                    tokenize='unicode61'
                )
                """);

        // 组装 Bean
        properties = new DataStoreProperties();
        properties.setMaxCollections(5);
        properties.setMaxDocumentsPerCollection(10);
        properties.setMaxDocumentSizeBytes(1024);

        var collectionRepo = new CollectionRepository(jdbcTemplate);
        var documentRepo = new DocumentRepository(jdbcTemplate);
        var queryEngine = new QueryEngine(properties);
        var aggregationEngine = new AggregationEngine();
        var propertyValidator = new PropertyValidator();

        manager = new DataStoreManager(
                collectionRepo, documentRepo, queryEngine,
                aggregationEngine, propertyValidator, properties);
    }

    // ==================== 集合 CRUD ====================

    @Test
    @Order(1)
    void 创建DOCUMENT集合_成功() {
        var propDefs = List.of(
                new PropertyDefinition("title", PropertyType.TEXT, true, "标题"),
                new PropertyDefinition("priority", PropertyType.NUMBER, false, "优先级")
        );
        var collection = manager.createCollection("todos", CollectionType.DOCUMENT,
                propDefs, "待办事项", "test-user");

        assertThat(collection.id()).isNotBlank();
        assertThat(collection.name()).isEqualTo("todos");
        assertThat(collection.type()).isEqualTo(CollectionType.DOCUMENT);
        assertThat(collection.description()).isEqualTo("待办事项");
        assertThat(collection.createdBy()).isEqualTo("test-user");
        assertThat(collection.propertiesJson()).contains("title");

        docCollectionId = collection.id();
    }

    @Test
    @Order(2)
    void 创建NOTE集合_成功() {
        var collection = manager.createCollection("notes", CollectionType.NOTE,
                null, "笔记", null);

        assertThat(collection.type()).isEqualTo(CollectionType.NOTE);
        noteCollectionId = collection.id();
    }

    @Test
    @Order(3)
    void 创建METRIC集合_成功() {
        var propDefs = List.of(
                new PropertyDefinition("weight", PropertyType.NUMBER, true, "体重")
        );
        var collection = manager.createCollection("health", CollectionType.METRIC,
                propDefs, "健康指标", null);

        assertThat(collection.type()).isEqualTo(CollectionType.METRIC);
        metricCollectionId = collection.id();
    }

    @Test
    @Order(4)
    void 创建重名集合_抛出异常() {
        assertThatThrownBy(() ->
                manager.createCollection("todos", CollectionType.DOCUMENT, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合名称已存在");
    }

    @Test
    @Order(5)
    void 集合数量达上限_抛出异常() {
        // 已有 3 个集合，上限 5，再创建 2 个填满
        manager.createCollection("col4", CollectionType.DOCUMENT, null, null, null);
        manager.createCollection("col5", CollectionType.DOCUMENT, null, null, null);

        assertThatThrownBy(() ->
                manager.createCollection("col6", CollectionType.DOCUMENT, null, null, null))
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
    void 按类型查询集合() {
        var docs = manager.listCollections(CollectionType.DOCUMENT);
        assertThat(docs).hasSizeGreaterThanOrEqualTo(3);

        var notes = manager.listCollections(CollectionType.NOTE);
        assertThat(notes).hasSize(1);
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
        boolean updated = manager.updateCollection(docCollectionId, "更新后的描述", """
                {"version": 2}""");
        assertThat(updated).isTrue();

        var collection = manager.findCollection("todos").orElseThrow();
        assertThat(collection.description()).isEqualTo("更新后的描述");
    }

    // ==================== 文档 CRUD ====================

    @Test
    @Order(10)
    void 添加文档_DOCUMENT类型_成功() {
        var doc = manager.addDocument(docCollectionId, """
                {"title": "买牛奶", "priority": 1}""", null);

        assertThat(doc.id()).isNotBlank();
        assertThat(doc.collectionId()).isEqualTo(docCollectionId);
        assertThat(doc.dataJson()).contains("买牛奶");

        documentId = doc.id();
    }

    @Test
    @Order(11)
    void 添加文档_属性校验失败_抛出异常() {
        // title 是必填 TEXT，传入数字
        assertThatThrownBy(() ->
                manager.addDocument(docCollectionId, """
                        {"title": 123, "priority": 1}""", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性校验失败");
    }

    @Test
    @Order(12)
    void 添加文档_缺少必填属性_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument(docCollectionId, """
                        {"priority": 1}""", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性校验失败");
    }

    @Test
    @Order(13)
    void 添加文档_无效JSON_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument(docCollectionId, "not-json", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效的 JSON");
    }

    @Test
    @Order(14)
    void 添加文档_超过大小限制_抛出异常() {
        // maxDocumentSizeBytes = 1024
        String largeJson = """
                {"title": "%s"}""".formatted("x".repeat(2000));
        assertThatThrownBy(() ->
                manager.addDocument(docCollectionId, largeJson, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档大小超过限制");
    }

    @Test
    @Order(15)
    void 添加文档_集合不存在_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument("nonexistent-id", """
                        {"data": "test"}""", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合不存在");
    }

    @Test
    @Order(16)
    void 获取文档() {
        var doc = manager.getDocument(documentId);
        assertThat(doc).isPresent();
        assertThat(doc.get().dataJson()).contains("买牛奶");

        var notFound = manager.getDocument("nonexistent");
        assertThat(notFound).isEmpty();
    }

    @Test
    @Order(17)
    void 更新文档_成功() {
        boolean updated = manager.updateDocument(documentId, """
                {"title": "买酸奶", "priority": 2}""");
        assertThat(updated).isTrue();

        var doc = manager.getDocument(documentId).orElseThrow();
        assertThat(doc.dataJson()).contains("买酸奶");
    }

    @Test
    @Order(18)
    void 更新文档_属性校验失败() {
        assertThatThrownBy(() ->
                manager.updateDocument(documentId, """
                        {"title": 999}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性校验失败");
    }

    @Test
    @Order(19)
    void 更新文档_不存在_抛出异常() {
        assertThatThrownBy(() ->
                manager.updateDocument("nonexistent", """
                        {"title": "test"}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    // ==================== 查询 ====================

    @Test
    @Order(20)
    void 条件查询_EQ过滤() {
        // 先多加几条文档
        manager.addDocument(docCollectionId, """
                {"title": "写代码", "priority": 3}""", null);
        manager.addDocument(docCollectionId, """
                {"title": "看书", "priority": 2}""", null);

        var request = new QueryRequest(
                docCollectionId,
                List.of(new QueryFilter("priority", FilterOp.EQ, 2)),
                null, null, 0, 10
        );
        var results = manager.queryDocuments(request);

        // "买酸奶" priority=2 和 "看书" priority=2
        assertThat(results).hasSize(2);
    }

    @Test
    @Order(21)
    void 条件查询_GT过滤() {
        var request = new QueryRequest(
                docCollectionId,
                List.of(new QueryFilter("priority", FilterOp.GT, 2)),
                null, null, 0, 10
        );
        var results = manager.queryDocuments(request);

        // "写代码" priority=3
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().dataJson()).contains("写代码");
    }

    @Test
    @Order(22)
    void 条件查询_CONTAINS过滤() {
        var request = new QueryRequest(
                docCollectionId,
                List.of(new QueryFilter("title", FilterOp.CONTAINS, "酸奶")),
                null, null, 0, 10
        );
        var results = manager.queryDocuments(request);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().dataJson()).contains("买酸奶");
    }

    @Test
    @Order(23)
    void 条件查询_排序() {
        var request = new QueryRequest(
                docCollectionId,
                List.of(),
                "priority", SortDirection.DESC, 0, 10
        );
        var results = manager.queryDocuments(request);

        assertThat(results).hasSizeGreaterThanOrEqualTo(3);
        // 第一条应该是 priority 最高的
        assertThat(results.getFirst().dataJson()).contains("写代码");
    }

    @Test
    @Order(24)
    void 条件查询_分页() {
        var request = new QueryRequest(
                docCollectionId,
                List.of(),
                "priority", SortDirection.ASC, 0, 2
        );
        var page1 = manager.queryDocuments(request);
        assertThat(page1).hasSize(2);

        var request2 = new QueryRequest(
                docCollectionId,
                List.of(),
                "priority", SortDirection.ASC, 2, 2
        );
        var page2 = manager.queryDocuments(request2);
        assertThat(page2).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(25)
    void 条件查询_集合不存在_抛出异常() {
        var request = new QueryRequest("nonexistent", List.of(), null, null, 0, 10);
        assertThatThrownBy(() -> manager.queryDocuments(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("集合不存在");
    }

    // ==================== FTS5 全文搜索 ====================

    @Test
    @Order(30)
    void NOTE集合_全文搜索() {
        // 添加几条笔记
        manager.addDocument(noteCollectionId, """
                {"content": "今天学习了 Spring Boot 自动配置原理"}""", null);
        manager.addDocument(noteCollectionId, """
                {"content": "Java 22 的虚拟线程非常好用"}""", null);
        manager.addDocument(noteCollectionId, """
                {"content": "SQLite FTS5 全文索引测试"}""", null);

        // 搜索 "Spring"
        var results = manager.searchDocuments(noteCollectionId, "Spring", 10);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().dataJson()).contains("Spring Boot");
    }

    @Test
    @Order(31)
    void NOTE集合_全文搜索_多结果() {
        var results = manager.searchDocuments(noteCollectionId, "Java OR SQLite", 10);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(32)
    void 非NOTE集合_全文搜索_抛出异常() {
        assertThatThrownBy(() ->
                manager.searchDocuments(docCollectionId, "test", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全文搜索仅支持 NOTE 类型集合");
    }

    // ==================== METRIC 时序聚合 ====================

    @Test
    @Order(40)
    void METRIC集合_添加文档_需要recordedAt() {
        assertThatThrownBy(() ->
                manager.addDocument(metricCollectionId, """
                        {"weight": 70.5}""", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("METRIC 类型文档必须包含 recordedAt");
    }

    @Test
    @Order(41)
    void METRIC集合_recordedAt格式非法_抛出异常() {
        assertThatThrownBy(() ->
                manager.addDocument(metricCollectionId, """
                        {"weight": 70.5}""", "not-a-date"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordedAt 格式非法");
    }

    @Test
    @Order(42)
    void METRIC集合_添加文档_成功() {
        manager.addDocument(metricCollectionId, """
                {"weight": 70.5}""", "2026-03-01T08:00:00");
        manager.addDocument(metricCollectionId, """
                {"weight": 71.0}""", "2026-03-02T08:00:00");
        manager.addDocument(metricCollectionId, """
                {"weight": 69.8}""", "2026-03-03T08:00:00");
        manager.addDocument(metricCollectionId, """
                {"weight": 72.0}""", "2026-03-15T08:00:00");
        manager.addDocument(metricCollectionId, """
                {"weight": 68.5}""", "2026-03-16T08:00:00");

        // 验证文档已添加
        var request = new QueryRequest(metricCollectionId, List.of(), null, null, 0, 100);
        var docs = manager.queryDocuments(request);
        assertThat(docs).hasSize(5);
    }

    @Test
    @Order(43)
    void 时序聚合_SUM_无分组() {
        var request = new AggregationRequest(
                metricCollectionId, "weight", AggregateFunction.SUM,
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
                metricCollectionId, "weight", AggregateFunction.AVG,
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
                metricCollectionId, "weight", AggregateFunction.AVG,
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
                metricCollectionId, "weight", AggregateFunction.MAX,
                null, null, null);
        var results = manager.aggregate(request);

        assertThat(results.getFirst().value()).isCloseTo(72.0, within(0.01));
    }

    @Test
    @Order(47)
    void 时序聚合_MIN() {
        var request = new AggregationRequest(
                metricCollectionId, "weight", AggregateFunction.MIN,
                null, null, null);
        var results = manager.aggregate(request);

        assertThat(results.getFirst().value()).isCloseTo(68.5, within(0.01));
    }

    @Test
    @Order(48)
    void 非METRIC集合_聚合_抛出异常() {
        var request = new AggregationRequest(
                docCollectionId, "priority", AggregateFunction.SUM,
                null, null, null);
        assertThatThrownBy(() -> manager.aggregate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时序聚合仅支持 METRIC 类型集合");
    }

    // ==================== 文档删除 ====================

    @Test
    @Order(50)
    void 删除文档_成功() {
        boolean deleted = manager.deleteDocument(documentId);
        assertThat(deleted).isTrue();

        var doc = manager.getDocument(documentId);
        assertThat(doc).isEmpty();
    }

    @Test
    @Order(51)
    void 删除文档_不存在_抛出异常() {
        assertThatThrownBy(() -> manager.deleteDocument("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }

    // ==================== NOTE 文档删除时 FTS 清理 ====================

    @Test
    @Order(52)
    void NOTE文档删除_FTS同步清理() {
        // 添加一条笔记
        var doc = manager.addDocument(noteCollectionId, """
                {"content": "临时笔记用于删除测试"}""", null);

        // 搜索能找到
        var before = manager.searchDocuments(noteCollectionId, "临时笔记", 10);
        assertThat(before).hasSize(1);

        // 删除
        manager.deleteDocument(doc.id());

        // 搜索不再找到
        var after = manager.searchDocuments(noteCollectionId, "临时笔记", 10);
        assertThat(after).isEmpty();
    }

    // ==================== NOTE 文档更新时 FTS 同步 ====================

    @Test
    @Order(53)
    void NOTE文档更新_FTS同步更新() {
        var doc = manager.addDocument(noteCollectionId, """
                {"content": "原始内容关键词AAA"}""", null);

        // 搜索原始内容
        var before = manager.searchDocuments(noteCollectionId, "AAA", 10);
        assertThat(before).hasSize(1);

        // 更新内容
        manager.updateDocument(doc.id(), """
                {"content": "更新后内容关键词BBB"}""");

        // 旧关键词搜不到
        var afterOld = manager.searchDocuments(noteCollectionId, "AAA", 10);
        assertThat(afterOld).isEmpty();

        // 新关键词能搜到
        var afterNew = manager.searchDocuments(noteCollectionId, "BBB", 10);
        assertThat(afterNew).hasSize(1);
    }

    // ==================== 集合删除级联 ====================

    @Test
    @Order(60)
    void 删除集合_级联删除文档() {
        // 创建临时集合（先删一个腾出配额）
        // 删除 col4 和 col5 腾出空间
        var col4 = manager.findCollection("col4").orElseThrow();
        var col5 = manager.findCollection("col5").orElseThrow();
        manager.deleteCollection(col4.id());
        manager.deleteCollection(col5.id());

        // 创建新集合
        var tempCol = manager.createCollection("temp-cascade", CollectionType.DOCUMENT,
                null, null, null);
        manager.addDocument(tempCol.id(), """
                {"data": "will be cascaded"}""", null);
        manager.addDocument(tempCol.id(), """
                {"data": "also cascaded"}""", null);

        // 删除集合
        boolean deleted = manager.deleteCollection(tempCol.id());
        assertThat(deleted).isTrue();

        // 集合不存在
        assertThat(manager.findCollection("temp-cascade")).isEmpty();
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
        var col = manager.createCollection("limit-test", CollectionType.DOCUMENT,
                null, null, null);

        for (int i = 0; i < 10; i++) {
            manager.addDocument(col.id(), """
                    {"index": %d}""".formatted(i), null);
        }

        assertThatThrownBy(() ->
                manager.addDocument(col.id(), """
                        {"index": 10}""", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("集合文档数量已达上限");
    }
}
