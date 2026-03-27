package com.lifepilot.datastore;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.repository.DocumentRepository;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
import com.lifepilot.datastore.validation.PropertyValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DataStoreManager 创建集合回滚测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class DataStoreManagerCreateCollectionRollbackTest {

    @Test
    void createCollection_内部知识库创建失败_不应留下半成品集合() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.execute("""
                CREATE TABLE ds_collections (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    description TEXT,
                    type TEXT NOT NULL DEFAULT 'DOCUMENT',
                    properties_json TEXT,
                    projection_config_json TEXT NOT NULL DEFAULT '{}',
                    metadata_json TEXT,
                    default_knowledge_base_id TEXT,
                    created_by TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE ds_documents (
                    id TEXT PRIMARY KEY,
                    collection_id TEXT NOT NULL,
                    data_json TEXT NOT NULL DEFAULT '{}',
                    recorded_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE ds_documents_fts USING fts5(
                    document_id, content,
                    tokenize='unicode61'
                )
                """);

        var properties = new DataStoreProperties();
        properties.setMaxCollections(5);
        properties.setMaxDocumentsPerCollection(10);
        properties.setMaxDocumentSizeBytes(1024 * 1024);

        var collectionRepository = new CollectionRepository(jdbcTemplate);
        var documentRepository = new DocumentRepository(jdbcTemplate);
        var manager = new DataStoreManager(
                collectionRepository,
                documentRepository,
                new QueryEngine(properties),
                new AggregationEngine(),
                new PropertyValidator(),
                properties,
                null,
                new DatastoreKnowledgeBaseProvisioner() {
                    @Override
                    public String ensureDefaultKnowledgeBase(Collection collection) {
                        throw new IllegalStateException("内部知识库创建失败");
                    }

                    @Override
                    public void deleteDefaultKnowledgeBase(Collection collection) {
                        // no-op
                    }
                }
        );

        assertThatThrownBy(() -> manager.createCollection(
                "novel-workspace",
                CollectionType.DOCUMENT,
                null,
                "小说创作数据存储",
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内部知识库创建失败");

        assertThat(collectionRepository.findByName("novel-workspace")).isEmpty();
    }

    private JdbcTemplate createJdbcTemplate() {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        var sqliteDataSource = new SQLiteDataSource(config);
        sqliteDataSource.setUrl("jdbc:sqlite::memory:");
        var dataSource = new SingleConnectionDataSource(sqliteDataSource.getUrl(), false);
        return new JdbcTemplate(dataSource);
    }
}
