package com.lifepilot.datastore.engine;

import com.lifepilot.datastore.config.DataStoreProperties;
import com.lifepilot.datastore.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryEngine 单元测试 — 验证动态 SQL 构建逻辑。
 *
 * @author zsg
 * @since 2026-03-20
 */
class QueryEngine单元测试 {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        var props = new DataStoreProperties();
        props.setDefaultPageSize(20);
        props.setMaxPageSize(100);
        engine = new QueryEngine(props);
    }

    // ---- 基础查询 ----

    @Test
    void 无过滤条件_生成基础SQL() {
        var request = new QueryRequest("col-123", List.of(), null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("SELECT * FROM ds_documents WHERE collection_id = ?");
        assertThat(result.sql()).contains("LIMIT ?");
        assertThat(result.params()).contains("col-123");
        assertThat(result.params()).contains(10);
    }

    @Test
    void limit为零_使用默认分页大小() {
        var request = new QueryRequest("col-123", List.of(), null, null, 0, 0);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        // defaultPageSize = 20
        assertThat(result.params()).contains(20);
    }

    @Test
    void limit超过最大值_截断到最大分页大小() {
        var request = new QueryRequest("col-123", List.of(), null, null, 0, 999);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        // maxPageSize = 100
        assertThat(result.params()).contains(100);
    }

    @Test
    void 有offset_生成OFFSET子句() {
        var request = new QueryRequest("col-123", List.of(), null, null, 50, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("OFFSET ?");
        assertThat(result.params()).contains(50);
    }

    // ---- 过滤条件 ----

    @Test
    void EQ过滤_生成等号条件() {
        var filters = List.of(new QueryFilter("status", FilterOp.EQ, "active"));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("json_extract(data_json, '$.status') = ?");
        assertThat(result.params()).contains("active");
    }

    @Test
    void GT过滤_生成大于条件() {
        var filters = List.of(new QueryFilter("score", FilterOp.GT, 80));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("json_extract(data_json, '$.score') > ?");
        assertThat(result.params()).contains(80);
    }

    @Test
    void CONTAINS过滤_生成LIKE条件() {
        var filters = List.of(new QueryFilter("name", FilterOp.CONTAINS, "test"));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("json_extract(data_json, '$.name') LIKE ?");
        assertThat(result.params()).contains("%test%");
    }

    @Test
    void IN过滤_展开多个占位符() {
        var filters = List.of(new QueryFilter("status", FilterOp.IN, List.of("a", "b", "c")));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("IN (?, ?, ?)");
        assertThat(result.params()).contains("a", "b", "c");
    }

    @Test
    void IN过滤_空列表_生成永假条件() {
        var filters = List.of(new QueryFilter("status", FilterOp.IN, List.of()));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("1 = 0");
    }

    @Test
    void IN过滤_单值_退化为EQ() {
        var filters = List.of(new QueryFilter("status", FilterOp.IN, "active"));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("= ?");
        assertThat(result.params()).contains("active");
    }

    @Test
    void 多个过滤条件_AND组合() {
        var filters = List.of(
                new QueryFilter("status", FilterOp.EQ, "active"),
                new QueryFilter("score", FilterOp.GTE, 60)
        );
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        // 两个 AND 子句
        var sql = result.sql();
        assertThat(sql.indexOf("AND")).isGreaterThan(0);
        assertThat(sql.indexOf("AND", sql.indexOf("AND") + 1)).isGreaterThan(0);
    }

    // ---- 索引感知 ----

    @Test
    void 索引字段_使用索引列名() {
        var filters = List.of(new QueryFilter("status", FilterOp.EQ, "active"));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of("status"), "col-1234");

        assertThat(result.sql()).contains("_idx_col-1234_status = ?");
        assertThat(result.sql()).doesNotContain("json_extract");
    }

    @Test
    void 非索引字段_使用json_extract() {
        var filters = List.of(new QueryFilter("name", FilterOp.EQ, "test"));
        var request = new QueryRequest("col-123", filters, null, null, 0, 10);
        var result = engine.buildQuery(request, Set.of("status"), "col-1234");

        assertThat(result.sql()).contains("json_extract(data_json, '$.name')");
    }

    // ---- 排序 ----

    @Test
    void 排序字段_生成ORDER_BY子句() {
        var request = new QueryRequest("col-123", List.of(), "score", SortDirection.DESC, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("ORDER BY json_extract(data_json, '$.score') DESC");
    }

    @Test
    void 排序字段_无方向_默认ASC() {
        var request = new QueryRequest("col-123", List.of(), "name", null, 0, 10);
        var result = engine.buildQuery(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("ORDER BY json_extract(data_json, '$.name') ASC");
    }

    @Test
    void 排序字段_索引感知() {
        var request = new QueryRequest("col-123", List.of(), "score", SortDirection.ASC, 0, 10);
        var result = engine.buildQuery(request, Set.of("score"), "col-1234");

        assertThat(result.sql()).contains("ORDER BY _idx_col-1234_score ASC");
    }
}
