package com.lifepilot.datastore.repository;

import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
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
 * <p>提供集合的 CRUD 操作，以及 Generated Column 动态 DDL 管理。
 * Generated Column 用于为 JSON 属性创建 B-tree 索引，加速查询。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class CollectionRepository {

    private static final Logger log = LoggerFactory.getLogger(CollectionRepository.class);
    private static final String DEFAULT_PROJECTION_CONFIG_JSON = "{}";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Collection> rowMapper;

    public CollectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 插入新集合。
     *
     * @param collection 集合数据（id、createdAt、updatedAt 由系统生成）
     * @return 生成的集合 UUID
     */
    public String insert(Collection collection) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        jdbcTemplate.update("""
                INSERT INTO ds_collections (id, name, description, type, properties_json, projection_config_json, metadata_json, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, collection.name(), collection.description(),
                collection.type().name(), collection.propertiesJson(),
                normalizeProjectionConfigJson(collection.projectionConfigJson()), collection.metadataJson(),
                collection.createdBy(), now, now);

        log.info("集合创建成功: id={}, name={}", id, collection.name());
        return id;
    }

    /**
     * 根据 ID 查找集合。
     *
     * @param id 集合 ID
     * @return 集合 Optional，不存在时返回 empty
     */
    public Optional<Collection> findById(String id) {
        List<Collection> results = jdbcTemplate.query(
                "SELECT * FROM ds_collections WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 根据名称查找集合。
     *
     * @param name 集合名称
     * @return 集合 Optional，不存在时返回 empty
     */
    public Optional<Collection> findByName(String name) {
        List<Collection> results = jdbcTemplate.query(
                "SELECT * FROM ds_collections WHERE name = ?", rowMapper, name);
        return results.stream().findFirst();
    }

    /**
     * 按类型查询集合列表。
     *
     * @param type 集合类型
     * @return 匹配类型的集合列表
     */
    public List<Collection> findByType(CollectionType type) {
        return jdbcTemplate.query(
                "SELECT * FROM ds_collections WHERE type = ? ORDER BY created_at DESC",
                rowMapper, type.name());
    }

    /**
     * 查询所有集合。
     *
     * @return 所有集合列表，按创建时间降序
     */
    public List<Collection> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM ds_collections ORDER BY created_at DESC", rowMapper);
    }

    /**
     * 更新集合的描述和元数据。
     *
     * @param id           集合 ID
     * @param description  新描述
     * @param projectionConfigJson 新投影配置 JSON
     * @param metadataJson 新元数据 JSON
     * @return 是否更新成功
     */
    public boolean update(String id,
                          @Nullable String description,
                          @Nullable String projectionConfigJson,
                          @Nullable String metadataJson) {
        String now = Instant.now().toString();
        int rows = jdbcTemplate.update("""
                UPDATE ds_collections
                SET description = ?, projection_config_json = ?, metadata_json = ?, updated_at = ?
                WHERE id = ?
                """,
                description, normalizeProjectionConfigJson(projectionConfigJson), metadataJson, now, id);

        if (rows > 0) {
            log.info("集合更新成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 删除集合。
     *
     * <p>关联文档由 FK ON DELETE CASCADE 自动删除。</p>
     *
     * @param id 集合 ID
     * @return 是否删除成功
     */
    public boolean delete(String id) {
        int rows = jdbcTemplate.update("DELETE FROM ds_collections WHERE id = ?", id);
        if (rows > 0) {
            log.info("集合删除成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 统计集合总数。
     *
     * @return 集合数量
     */
    public int count() {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ds_collections", Integer.class);
        return result != null ? result : 0;
    }

    // ---- Generated Column 管理 ----

    /**
     * 为文档表添加 Generated Column 和 partial index。
     *
     * <p>列名格式：{@code _idx_{collectionId前8位}_{propertyName}}<br/>
     * 索引名格式：{@code idx_ds_doc_{collectionId前8位}_{propertyName}}<br/>
     * Generated Column 表达式：{@code json_extract(data_json, '$.{propertyName}')}<br/>
     * Partial index 条件：{@code WHERE collection_id = '{collectionId}'}</p>
     *
     * @param propertyName    属性名称
     * @param sqliteAffinity  SQLite 列亲和性（TEXT / REAL / INTEGER）
     * @param collectionId    集合 ID
     */
    public void addGeneratedColumn(String propertyName, String sqliteAffinity, String collectionId) {
        String prefix = collectionId.substring(0, 8);
        String columnName = "_idx_%s_%s".formatted(prefix, propertyName);
        String indexName = "idx_ds_doc_%s_%s".formatted(prefix, propertyName);

        // ALTER TABLE 添加 VIRTUAL Generated Column
        String alterSql = "ALTER TABLE ds_documents ADD COLUMN %s %s GENERATED ALWAYS AS (json_extract(data_json, '$.%s')) VIRTUAL"
                .formatted(columnName, sqliteAffinity, propertyName);
        log.info("添加 Generated Column: column={}, affinity={}, collection={}", columnName, sqliteAffinity, collectionId);
        jdbcTemplate.execute(alterSql);

        // CREATE partial index
        String indexSql = "CREATE INDEX %s ON ds_documents(%s) WHERE collection_id = '%s'"
                .formatted(indexName, columnName, collectionId);
        log.info("创建 partial index: index={}, collection={}", indexName, collectionId);
        jdbcTemplate.execute(indexSql);
    }

    /**
     * 删除指定集合的 Generated Column 索引。
     *
     * <p>SQLite 不支持 DROP COLUMN，因此只删除索引。
     * Generated Column 本身为 VIRTUAL，不占用存储空间。</p>
     *
     * @param collectionId  集合 ID
     * @param propertyNames 属性名称列表
     */
    public void dropGeneratedColumns(String collectionId, List<String> propertyNames) {
        String prefix = collectionId.substring(0, 8);
        for (String propertyName : propertyNames) {
            String indexName = "idx_ds_doc_%s_%s".formatted(prefix, propertyName);
            String dropSql = "DROP INDEX IF EXISTS %s".formatted(indexName);
            log.info("删除 Generated Column 索引: index={}, collection={}", indexName, collectionId);
            jdbcTemplate.execute(dropSql);
        }
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 Collection record。 */
    private Collection mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Collection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                CollectionType.valueOf(rs.getString("type")),
                rs.getString("properties_json"),
                normalizeProjectionConfigJson(rs.getString("projection_config_json")),
                rs.getString("metadata_json"),
                rs.getString("created_by"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    private String normalizeProjectionConfigJson(@Nullable String projectionConfigJson) {
        return projectionConfigJson != null ? projectionConfigJson : DEFAULT_PROJECTION_CONFIG_JSON;
    }
}
