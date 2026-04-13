package com.lifepilot.datastore.repository;

import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 集合仓储 — 基于 JdbcTemplate 操作 ds_collections 表。
 *
 * @author zsg
 * @since 2026-04-13
 */
public class CollectionRepository {

    private static final Logger log = LoggerFactory.getLogger(CollectionRepository.class);
    private static final String COLUMNS =
            "id, name, description, time_series, field_hints_json, " +
            "default_knowledge_base_id, created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Collection> rowMapper;

    public CollectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    public String insert(Collection collection) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        jdbcTemplate.update("""
                INSERT INTO ds_collections (
                    id, name, description, time_series, field_hints_json,
                    default_knowledge_base_id, created_by, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, collection.name(), collection.description(),
                collection.timeSeries() ? 1 : 0, collection.fieldHintsJson(),
                collection.defaultKnowledgeBaseId(), collection.createdBy(), now, now);

        log.info("集合创建成功: id={}, name={}", id, collection.name());
        return id;
    }

    public Optional<Collection> findById(String id) {
        List<Collection> results = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_collections WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    public Optional<Collection> findByName(String name) {
        List<Collection> results = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_collections WHERE name = ?", rowMapper, name);
        return results.stream().findFirst();
    }

    /** 按 time_series 标记查询集合。 */
    public List<Collection> findByTimeSeries(boolean timeSeries) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_collections WHERE time_series = ? ORDER BY created_at DESC",
                rowMapper, timeSeries ? 1 : 0);
    }

    public List<Collection> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM ds_collections ORDER BY created_at DESC", rowMapper);
    }

    /**
     * 更新集合的描述和字段提示。
     */
    public boolean update(String id,
                          @Nullable String description,
                          @Nullable String fieldHintsJson) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE ds_collections
                SET description = ?, field_hints_json = ?, updated_at = ?
                WHERE id = ?
                """,
                description, fieldHintsJson, now, id);

        if (rows > 0) {
            log.info("集合更新成功: id={}", id);
        }
        return rows > 0;
    }

    public boolean updateDefaultKnowledgeBaseId(String id, @Nullable String defaultKnowledgeBaseId) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE ds_collections
                SET default_knowledge_base_id = ?, updated_at = ?
                WHERE id = ?
                """,
                defaultKnowledgeBaseId, now, id);
        if (rows > 0) {
            log.info("集合默认知识库已更新: id={}, knowledgeBaseId={}", id, defaultKnowledgeBaseId);
        }
        return rows > 0;
    }

    public boolean delete(String id) {
        int rows = jdbcTemplate.update("DELETE FROM ds_collections WHERE id = ?", id);
        if (rows > 0) {
            log.info("集合删除成功: id={}", id);
        }
        return rows > 0;
    }

    public int count() {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ds_collections", Integer.class);
        return result != null ? result : 0;
    }

    // ---- Generated Column 管理（委托 documents 表）----

    /**
     * 为 documents 表添加 Generated Column 和 partial index。
     *
     * <p>列名格式：{@code _idx_{datastoreId前8位}_{fieldName}}<br/>
     * Generated Column 表达式：{@code json_extract(metadata_json, '$.{fieldName}')}<br/>
     * Partial index 条件：{@code WHERE source_datastore_id = '{datastoreId}'}</p>
     */
    public void addGeneratedColumn(String fieldName, String sqliteAffinity, String datastoreId) {
        FieldNames.validate(fieldName);
        FieldNames.validateId(datastoreId);
        String prefix = FieldNames.safePrefix(datastoreId);
        String columnName = "_idx_%s_%s".formatted(prefix, fieldName);
        String indexName = "idx_ds_doc_%s_%s".formatted(prefix, fieldName);

        String alterSql = "ALTER TABLE documents ADD COLUMN %s %s GENERATED ALWAYS AS (json_extract(metadata_json, '$.%s')) VIRTUAL"
                .formatted(columnName, sqliteAffinity, fieldName);
        log.info("添加 Generated Column: column={}, affinity={}, datastore={}", columnName, sqliteAffinity, datastoreId);
        jdbcTemplate.execute(alterSql);

        String indexSql = "CREATE INDEX %s ON documents(%s) WHERE source_datastore_id = '%s'"
                .formatted(indexName, columnName, datastoreId);
        log.info("创建 partial index: index={}, datastore={}", indexName, datastoreId);
        jdbcTemplate.execute(indexSql);
    }

    /**
     * 删除指定 datastore 的 Generated Column 索引。
     */
    public void dropGeneratedColumns(String datastoreId, List<String> fieldNames) {
        FieldNames.validateId(datastoreId);
        String prefix = FieldNames.safePrefix(datastoreId);
        for (String fieldName : fieldNames) {
            FieldNames.validate(fieldName);
            String indexName = "idx_ds_doc_%s_%s".formatted(prefix, fieldName);
            String dropSql = "DROP INDEX IF EXISTS %s".formatted(indexName);
            log.info("删除 Generated Column 索引: index={}, datastore={}", indexName, datastoreId);
            jdbcTemplate.execute(dropSql);
        }
    }

    private Collection mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Collection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("time_series") == 1,
                rs.getString("field_hints_json"),
                rs.getString("default_knowledge_base_id"),
                rs.getString("created_by"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
