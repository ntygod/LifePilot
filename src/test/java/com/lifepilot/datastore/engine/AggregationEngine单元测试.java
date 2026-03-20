package com.lifepilot.datastore.engine;

import com.lifepilot.datastore.model.AggregateFunction;
import com.lifepilot.datastore.model.AggregationRequest;
import com.lifepilot.datastore.model.TimeGranularity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AggregationEngine 单元测试 — 验证时序聚合 SQL 构建逻辑。
 *
 * @author zsg
 * @since 2026-03-20
 */
class AggregationEngine单元测试 {

    private final AggregationEngine engine = new AggregationEngine();

    // ---- 无分组聚合 ----

    @Test
    void 无分组_SUM聚合_生成total() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.SUM,
                null, null, null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("'total' AS time_bucket");
        assertThat(result.sql()).contains("SUM(json_extract(data_json, '$.weight'))");
        assertThat(result.sql()).contains("WHERE collection_id = ?");
        assertThat(result.params()).containsExactly("col-123");
    }

    @Test
    void 无分组_AVG聚合() {
        var request = new AggregationRequest("col-123", "score", AggregateFunction.AVG,
                null, null, null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("AVG(json_extract(data_json, '$.score'))");
    }

    @Test
    void 无分组_COUNT聚合() {
        var request = new AggregationRequest("col-123", "id", AggregateFunction.COUNT,
                null, null, null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("COUNT(json_extract(data_json, '$.id'))");
    }

    // ---- 时间分组 ----

    @Test
    void 按天分组_生成strftime() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.AVG,
                TimeGranularity.DAY, null, null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("strftime('%Y-%m-%d', recorded_at) AS time_bucket");
        assertThat(result.sql()).contains("GROUP BY 1");
        assertThat(result.sql()).contains("ORDER BY 1 ASC");
    }

    @Test
    void 按周分组() {
        var request = new AggregationRequest("col-123", "steps", AggregateFunction.SUM,
                TimeGranularity.WEEK, null, null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("strftime('%Y-W%W', recorded_at)");
    }

    @Test
    void 按月分组() {
        var request = new AggregationRequest("col-123", "revenue", AggregateFunction.SUM,
                TimeGranularity.MONTH, null, null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("strftime('%Y-%m', recorded_at)");
    }

    // ---- 时间范围过滤 ----

    @Test
    void 有startTime_生成大于等于条件() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.AVG,
                null, "2026-01-01T00:00:00", null);
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("recorded_at >= ?");
        assertThat(result.params()).contains("2026-01-01T00:00:00");
    }

    @Test
    void 有endTime_生成小于条件() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.AVG,
                null, null, "2026-04-01T00:00:00");
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("recorded_at < ?");
        assertThat(result.params()).contains("2026-04-01T00:00:00");
    }

    @Test
    void 时间范围_startTime和endTime同时存在() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.AVG,
                TimeGranularity.DAY, "2026-01-01T00:00:00", "2026-04-01T00:00:00");
        var result = engine.buildAggregation(request, Set.of(), "col-1234");

        assertThat(result.sql()).contains("recorded_at >= ?");
        assertThat(result.sql()).contains("recorded_at < ?");
        assertThat(result.params()).hasSize(3); // collectionId + startTime + endTime
    }

    // ---- 索引感知 ----

    @Test
    void 索引字段_使用索引列名() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.SUM,
                null, null, null);
        var result = engine.buildAggregation(request, Set.of("weight"), "col-1234");

        assertThat(result.sql()).contains("SUM(_idx_col-1234_weight)");
        assertThat(result.sql()).doesNotContain("json_extract");
    }

    @Test
    void 非索引字段_使用json_extract() {
        var request = new AggregationRequest("col-123", "weight", AggregateFunction.SUM,
                null, null, null);
        var result = engine.buildAggregation(request, Set.of("other"), "col-1234");

        assertThat(result.sql()).contains("json_extract(data_json, '$.weight')");
    }
}
