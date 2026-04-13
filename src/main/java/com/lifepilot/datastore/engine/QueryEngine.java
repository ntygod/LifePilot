package com.lifepilot.datastore.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.model.FieldNames;
import com.lifepilot.datastore.model.QueryFilter;
import com.lifepilot.datastore.model.QueryRequest;
import com.lifepilot.datastore.model.SqlWithParams;

/**
 * 动态查询引擎 — 将 {@link QueryRequest} 转换为参数化 SQL。
 *
 * <p>纯函数式组件，不持有 JdbcTemplate，只负责 SQL 构建。
 * 支持索引感知：有 Generated Column 的字段使用索引列名，
 * 无索引字段使用 {@code json_extract} 表达式。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class QueryEngine {

    private final DataStoreProperties properties;

    public QueryEngine(DataStoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建查询 SQL 和参数。
     *
     * @param request             查询请求
     * @param indexedFields       已有 Generated Column 索引的字段名称集合
     * @param collectionIdPrefix  集合 ID 前缀（用于构造索引列名 {@code _idx_{prefix}_{field}}）
     * @return SQL + 参数对
     */
    public SqlWithParams buildQuery(QueryRequest request,
                                    Set<String> indexedFields,
                                    String collectionIdPrefix) {
        var sql = new StringBuilder(
                "SELECT id, knowledge_base_id, file_name, file_path, file_size, mime_type, " +
                "content_hash, status, chunk_count, entity_count, error_message, " +
                "last_processed_stage, metadata_json, created_at, updated_at, " +
                "source_type, source_key, source_datastore_id, source_collection_id, " +
                "source_ref_json, content, recorded_at" +
                " FROM documents WHERE source_datastore_id = ? AND source_type = 'DATASTORE_DOCUMENT'");
        var params = new ArrayList<>();
        params.add(request.collectionId());

        // 过滤条件
        if (request.filters() != null) {
            for (QueryFilter filter : request.filters()) {
                FieldNames.validate(filter.field());
                String fieldExpr = resolveFieldExpression(filter.field(), indexedFields, collectionIdPrefix);
                appendFilter(sql, params, fieldExpr, filter);
            }
        }

        // 时间范围过滤
        if (request.startTime() != null) {
            sql.append(" AND COALESCE(recorded_at, created_at) >= ?");
            params.add(request.startTime());
        }
        if (request.endTime() != null) {
            sql.append(" AND COALESCE(recorded_at, created_at) < ?");
            params.add(request.endTime());
        }

        // 排序
        if (request.sortField() != null) {
            FieldNames.validate(request.sortField());
            String sortExpr = resolveFieldExpression(request.sortField(), indexedFields, collectionIdPrefix);
            var direction = request.sortDirection();
            sql.append(" ORDER BY ").append(sortExpr)
                    .append(" ").append(direction != null ? direction.name() : "ASC");
        }

        // 分页
        int limit = request.limit();
        if (limit <= 0) {
            limit = properties.getDefaultPageSize();
        }
        if (limit > properties.getMaxPageSize()) {
            limit = properties.getMaxPageSize();
        }
        sql.append(" LIMIT ?");
        params.add(limit);

        if (request.offset() > 0) {
            sql.append(" OFFSET ?");
            params.add(request.offset());
        }

        return new SqlWithParams(sql.toString(), params.toArray());
    }

    /**
     * 解析字段表达式 — 索引字段使用索引列名，非索引字段使用 json_extract。
     *
     * @param field              字段名称（已通过 {@link FieldNames#validate} 校验）
     * @param indexedFields      已索引字段集合
     * @param collectionIdPrefix 集合 ID 前缀
     * @return SQL 字段表达式
     */
    private String resolveFieldExpression(String field,
                                          Set<String> indexedFields,
                                          String collectionIdPrefix) {
        if (indexedFields.contains(field)) {
            return "_idx_" + collectionIdPrefix + "_" + field;
        }
        return "json_extract(metadata_json, '$." + field + "')";
    }

    /**
     * 追加单个过滤条件到 SQL。
     */
    private void appendFilter(StringBuilder sql, List<Object> params,
                              String fieldExpr, QueryFilter filter) {
        switch (filter.op()) {
            case IN -> appendInFilter(sql, params, fieldExpr, filter.value());
            case CONTAINS -> {
                sql.append(" AND ").append(fieldExpr).append(" LIKE ? ESCAPE '\\'");
                params.add("%" + escapeLikeWildcards(String.valueOf(filter.value())) + "%");
            }
            default -> {
                sql.append(" AND ").append(fieldExpr)
                        .append(" ").append(filter.op().toSql()).append(" ?");
                params.add(filter.value());
            }
        }
    }

    /**
     * 追加 IN 过滤条件 — 展开为多个 ? 占位符。
     */
    private void appendInFilter(StringBuilder sql, List<Object> params,
                                String fieldExpr, Object value) {
        if (value instanceof List<?> values) {
            if (values.isEmpty()) {
                // 空 IN 列表，添加永假条件
                sql.append(" AND 1 = 0");
                return;
            }
            sql.append(" AND ").append(fieldExpr).append(" IN (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(values.get(i));
            }
            sql.append(")");
        } else {
            // 单值退化为 EQ
            sql.append(" AND ").append(fieldExpr).append(" = ?");
            params.add(value);
        }
    }

    /** 转义 LIKE 通配符，防止用户输入的 % 和 _ 被当作通配符。 */
    private static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }
}
