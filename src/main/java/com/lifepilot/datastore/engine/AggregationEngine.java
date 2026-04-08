package com.lifepilot.datastore.engine;

import java.util.ArrayList;
import java.util.Set;

import com.lifepilot.datastore.model.FieldNames;
import com.lifepilot.datastore.model.AggregationRequest;
import com.lifepilot.datastore.model.SqlWithParams;

/**
 * 时序聚合引擎 — 将 {@link AggregationRequest} 转换为参数化 SQL。
 *
 * <p>纯函数式组件，不持有 JdbcTemplate，只负责聚合 SQL 构建。
 * 支持索引感知：有 Generated Column 的字段使用索引列名，
 * 无索引字段使用 {@code json_extract} 表达式。</p>
 *
 * <p>支持按时间粒度（DAY / WEEK / MONTH）分组，
 * 以及按时间范围（startTime / endTime）过滤。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class AggregationEngine {

    /**
     * 构建聚合 SQL 和参数。
     *
     * @param request            聚合请求
     * @param indexedFields      已有 Generated Column 索引的字段名称集合
     * @param collectionIdPrefix 集合 ID 前缀（用于构造索引列名 {@code _idx_{prefix}_{field}}）
     * @return SQL + 参数对
     */
    public SqlWithParams buildAggregation(AggregationRequest request,
                                          Set<String> indexedFields,
                                          String collectionIdPrefix) {
        var params = new ArrayList<>();
        FieldNames.validate(request.field());
        String fieldExpr = resolveFieldExpression(request.field(), indexedFields, collectionIdPrefix);
        String funcExpr = request.func().name() + "(" + fieldExpr + ")";
        var groupBy = request.groupBy();

        var sql = new StringBuilder();

        if (groupBy != null) {
            // 有时间分组：按 strftime 分桶
            String timeBucketExpr = "strftime(" + groupBy.toStrftime() + ", recorded_at)";
            sql.append("SELECT ").append(timeBucketExpr).append(" AS time_bucket, ")
                    .append(funcExpr)
                    .append(" FROM ds_documents WHERE collection_id = ?");
        } else {
            // 无时间分组：返回总计
            sql.append("SELECT 'total' AS time_bucket, ")
                    .append(funcExpr)
                    .append(" FROM ds_documents WHERE collection_id = ?");
        }
        params.add(request.collectionId());

        // 时间范围过滤
        if (request.startTime() != null) {
            sql.append(" AND recorded_at >= ?");
            params.add(request.startTime());
        }
        if (request.endTime() != null) {
            sql.append(" AND recorded_at < ?");
            params.add(request.endTime());
        }

        // 分组和排序
        if (groupBy != null) {
            sql.append(" GROUP BY 1");
        }
        sql.append(" ORDER BY 1 ASC");

        return new SqlWithParams(sql.toString(), params.toArray());
    }

    /**
     * 解析字段表达式 — 索引字段使用索引列名，非索引字段使用 json_extract。
     *
     * @param field              字段名称
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
        return "json_extract(data_json, '$." + field + "')";
    }
}
