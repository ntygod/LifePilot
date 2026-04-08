package com.lifepilot.datastore;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
import com.lifepilot.datastore.sync.DataStoreKnowledgeSyncPublisher;
import com.lifepilot.datastore.validation.PropertyValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DataStoreManager 投影配置测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class DataStoreManagerProjectionConfigTest {

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DataStoreKnowledgeSyncPublisher knowledgeSyncPublisher;
    private DataStoreManager manager;

    @BeforeEach
    void setUp() {
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);

        var sqliteDataSource = new SQLiteDataSource(config);
        sqliteDataSource.setUrl("jdbc:sqlite::memory:");
        dataSource = new SingleConnectionDataSource(sqliteDataSource.getUrl(), false);
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();

        var properties = new DataStoreProperties();
        properties.setMaxCollections(10);
        properties.setMaxDocumentsPerCollection(100);
        properties.setMaxDocumentSizeBytes(65536);

        knowledgeSyncPublisher = mock(DataStoreKnowledgeSyncPublisher.class);
        manager = new DataStoreManager(
                new CollectionRepository(jdbcTemplate),
                new DocumentRepository(jdbcTemplate),
                new QueryEngine(properties),
                new AggregationEngine(),
                new PropertyValidator(),
                properties,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                knowledgeSyncPublisher,
                null
        );
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void createCollection_保存投影配置() {
        String projectionConfigJson = """
                {"sections":[{"label":"角色卡","paths":["characters[*]"],"mode":"OBJECT_SUMMARY"}]}
                """;

        var collection = manager.createCollection(
                "novel-assets",
                CollectionType.DOCUMENT,
                null,
                "小说素材库",
                "tester",
                projectionConfigJson
        );

        assertThat(collection.projectionConfigJson()).isEqualTo(projectionConfigJson);
        assertThat(manager.findCollection("novel-assets"))
                .get()
                .extracting(com.lifepilot.datastore.model.Collection::projectionConfigJson)
                .isEqualTo(projectionConfigJson);
    }

    @Test
    void createCollection_未提供投影配置_默认保存空对象() {
        var collection = manager.createCollection(
                "generic-assets",
                CollectionType.DOCUMENT,
                null,
                "通用资料",
                "tester"
        );

        assertThat(collection.projectionConfigJson()).isEqualTo("{}");
        assertThat(manager.findCollection("generic-assets"))
                .get()
                .extracting(com.lifepilot.datastore.model.Collection::projectionConfigJson)
                .isEqualTo("{}");
    }

    @Test
    void updateCollection_投影配置变更_持久化并触发重同步() {
        var collection = manager.createCollection(
                "study-cards",
                CollectionType.DOCUMENT,
                null,
                "学习资料",
                "tester",
                "{\"scalarPaths\":[\"title\"]}"
        );
        String newProjectionConfig = """
                {"scalarPaths":["title"],"sections":[{"label":"知识卡","paths":["cards[*]"],"mode":"KEY_VALUE"}]}
                """;

        boolean updated = manager.updateCollection(collection.id(), null, null, newProjectionConfig);

        assertThat(updated).isTrue();
        assertThat(manager.findCollection("study-cards"))
                .get()
                .extracting(com.lifepilot.datastore.model.Collection::projectionConfigJson)
                .isEqualTo(newProjectionConfig);
        verify(knowledgeSyncPublisher).publishDatastoreResync(collection.id());
    }

    @Test
    void updateCollection_投影配置未变化_不触发重同步() {
        String projectionConfigJson = "{\"scalarPaths\":[\"title\"]}";
        var collection = manager.createCollection(
                "generic-notes",
                CollectionType.DOCUMENT,
                null,
                "通用笔记",
                "tester",
                projectionConfigJson
        );

        boolean updated = manager.updateCollection(collection.id(), "更新描述", null, projectionConfigJson);

        assertThat(updated).isTrue();
        verify(knowledgeSyncPublisher, never()).publishDatastoreResync(collection.id());
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE ds_collections (
                    id                     TEXT PRIMARY KEY,
                    name                   TEXT NOT NULL UNIQUE,
                    description            TEXT,
                    type                   TEXT NOT NULL DEFAULT 'DOCUMENT',
                    properties_json        TEXT,
                    projection_config_json TEXT NOT NULL DEFAULT '{}',
                    metadata_json          TEXT,
                    default_knowledge_base_id TEXT,
                    created_by             TEXT,
                    created_at             TEXT NOT NULL,
                    updated_at             TEXT NOT NULL
                )
                """);
    }
}
