package com.lifepilot.datastore;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.engine.AggregationEngine;
import com.lifepilot.datastore.engine.QueryEngine;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.datastore.sync.DatastoreKnowledgeBaseProvisioner;
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
                    metadata             TEXT NOT NULL DEFAULT '{}',
                    created_at           TEXT NOT NULL,
                    updated_at           TEXT NOT NULL,
                    source_type          TEXT NOT NULL DEFAULT 'FILE',
                    source_key           TEXT NOT NULL DEFAULT '',
                    source_datastore_id  TEXT,
                    source_collection_id TEXT,
                    source_ref           TEXT NOT NULL DEFAULT '{}',
                    content              TEXT,
                    recorded_at          TEXT
                )
                """);

        var properties = new DataStoreProperties();
        properties.setMaxCollections(5);
        properties.setMaxDocumentsPerCollection(10);
        properties.setMaxDocumentSizeBytes(1024 * 1024);

        var collectionRepository = new CollectionRepository(jdbcTemplate);
        var manager = new DataStoreManager(
                collectionRepository,
                new QueryEngine(properties),
                new AggregationEngine(),
                properties,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                null,
                null,
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
                false,
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
