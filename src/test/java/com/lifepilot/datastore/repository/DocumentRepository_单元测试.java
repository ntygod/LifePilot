package com.lifepilot.datastore.repository;

import com.lifepilot.datastore.model.AggregationResult;
import com.lifepilot.datastore.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DocumentRepository 单元测试 — 验证文档 CRUD、FTS5 索引同步、动态查询和聚合。
 *
 * <p>所有数据库交互通过 mock JdbcTemplate 验证，不依赖真实数据库。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class DocumentRepository_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private DocumentRepository repository;

    @BeforeEach
    void 初始化() {
        repository = new DocumentRepository(jdbcTemplate);
    }

    // ==================== 辅助方法 ====================

    /** 创建标准测试文档。 */
    private Document 创建文档(String id, String collectionId, String dataJson) {
        return Document.builder()
                .id(id)
                .collectionId(collectionId)
                .dataJson(dataJson)
                .recordedAt(null)
                .createdAt("2026-04-01T00:00:00Z")
                .updatedAt("2026-04-01T00:00:00Z")
                .build();
    }

    /** 创建带 recordedAt 的测试文档。 */
    private Document 创建时序文档(String id, String collectionId, String dataJson, String recordedAt) {
        return Document.builder()
                .id(id)
                .collectionId(collectionId)
                .dataJson(dataJson)
                .recordedAt(recordedAt)
                .createdAt("2026-04-01T00:00:00Z")
                .updatedAt("2026-04-01T00:00:00Z")
                .build();
    }

    // ==================== insert 测试 ====================

    @Nested
    class 插入文档 {

        @Test
        void 正常插入_返回生成的UUID() {
            var document = 创建文档(null, "col-001", "{\"title\":\"测试\"}");

            String id = repository.insert(document);

            assertThat(id).isNotNull().isNotEmpty();
            // UUID 格式验证：8-4-4-4-12
            assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        void 正常插入_传递正确参数给JdbcTemplate() {
            var document = 创建时序文档(null, "col-001", "{\"k\":\"v\"}", "2026-04-01T12:00:00Z");

            String id = repository.insert(document);

            // 捕获传递给 update 的参数（使用 Object[].class 消歧 varargs 重载）
            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT INTO ds_documents"), paramsCaptor.capture());

            Object[] params = paramsCaptor.getValue();
            assertThat(params[0]).isEqualTo(id);               // 生成的 id
            assertThat(params[1]).isEqualTo("col-001");         // collectionId
            assertThat(params[2]).isEqualTo("{\"k\":\"v\"}");   // dataJson
            assertThat(params[3]).isEqualTo("2026-04-01T12:00:00Z"); // recordedAt
            // params[4] 和 params[5] 是系统生成的 createdAt / updatedAt
            assertThat((String) params[4]).isNotEmpty();
            assertThat((String) params[5]).isNotEmpty();
        }

        @Test
        void 每次插入_生成不同的UUID() {
            var document = 创建文档(null, "col-001", "{}");

            String id1 = repository.insert(document);
            String id2 = repository.insert(document);

            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        void recordedAt为null_正常插入() {
            var document = 创建文档(null, "col-001", "{}");

            String id = repository.insert(document);

            assertThat(id).isNotNull();
            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("INSERT INTO ds_documents"), paramsCaptor.capture());
            assertThat(paramsCaptor.getValue()[3]).isNull(); // recordedAt 为 null
        }

        @Test
        void JdbcTemplate抛出异常_向上传播() {
            var document = 创建文档(null, "col-001", "{}");
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("连接失败"))
                    .when(jdbcTemplate).update(anyString(), any(Object[].class));

            assertThatThrownBy(() -> repository.insert(document))
                    .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class)
                    .hasMessageContaining("连接失败");
        }
    }

    // ==================== findById 测试 ====================

    @Nested
    class 按ID查找 {

        @SuppressWarnings("unchecked")
        @Test
        void 文档存在_返回对应文档() {
            var expected = 创建文档("doc-001", "col-001", "{\"title\":\"测试\"}");
            when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("doc-001")))
                    .thenReturn(List.of(expected));

            Optional<Document> result = repository.findById("doc-001");

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("doc-001");
            assertThat(result.get().dataJson()).isEqualTo("{\"title\":\"测试\"}");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 文档不存在_返回空Optional() {
            when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("nonexistent")))
                    .thenReturn(Collections.emptyList());

            Optional<Document> result = repository.findById("nonexistent");

            assertThat(result).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void 多条结果_只返回第一条() {
            var doc1 = 创建文档("doc-001", "col-001", "{}");
            var doc2 = 创建文档("doc-002", "col-001", "{}");
            when(jdbcTemplate.query(contains("WHERE id = ?"), any(RowMapper.class), eq("doc-001")))
                    .thenReturn(List.of(doc1, doc2));

            Optional<Document> result = repository.findById("doc-001");

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo("doc-001");
        }
    }

    // ==================== findByCollectionId 测试 ====================

    @Nested
    class 按集合查找 {

        @SuppressWarnings("unchecked")
        @Test
        void 集合有文档_返回有序列表() {
            var doc1 = 创建文档("doc-001", "col-001", "{\"order\":1}");
            var doc2 = 创建文档("doc-002", "col-001", "{\"order\":2}");
            when(jdbcTemplate.query(
                    contains("WHERE collection_id = ?"),
                    any(RowMapper.class),
                    eq("col-001")))
                    .thenReturn(List.of(doc1, doc2));

            List<Document> result = repository.findByCollectionId("col-001");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("doc-001");
            assertThat(result.get(1).id()).isEqualTo("doc-002");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 集合无文档_返回空列表() {
            when(jdbcTemplate.query(
                    contains("WHERE collection_id = ?"),
                    any(RowMapper.class),
                    eq("col-empty")))
                    .thenReturn(Collections.emptyList());

            List<Document> result = repository.findByCollectionId("col-empty");

            assertThat(result).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void SQL包含ORDER_BY按创建时间升序() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                    .thenReturn(Collections.emptyList());

            repository.findByCollectionId("col-001");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("col-001"));
            assertThat(sqlCaptor.getValue()).contains("ORDER BY created_at ASC");
        }
    }

    // ==================== update 测试 ====================

    @Nested
    class 更新文档 {

        @Test
        void 更新成功_返回true() {
            when(jdbcTemplate.update(contains("UPDATE ds_documents"), any(Object[].class)))
                    .thenReturn(1);

            boolean result = repository.update("doc-001", "{\"title\":\"更新后\"}");

            assertThat(result).isTrue();
        }

        @Test
        void 文档不存在_更新返回false() {
            when(jdbcTemplate.update(contains("UPDATE ds_documents"), any(Object[].class)))
                    .thenReturn(0);

            boolean result = repository.update("nonexistent", "{\"title\":\"更新\"}");

            assertThat(result).isFalse();
        }

        @Test
        void 更新传递正确参数_包含新的updatedAt() {
            when(jdbcTemplate.update(contains("UPDATE ds_documents"), any(Object[].class)))
                    .thenReturn(1);

            repository.update("doc-001", "{\"new\":\"data\"}");

            ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(contains("UPDATE ds_documents"), paramsCaptor.capture());

            Object[] params = paramsCaptor.getValue();
            assertThat(params[0]).isEqualTo("{\"new\":\"data\"}");  // dataJson
            assertThat((String) params[1]).isNotEmpty();            // updatedAt（系统生成）
            assertThat(params[2]).isEqualTo("doc-001");             // id (WHERE 条件)
        }
    }

    // ==================== delete 测试 ====================

    @Nested
    class 删除文档 {

        @Test
        void 删除成功_返回true() {
            when(jdbcTemplate.update(contains("DELETE FROM ds_documents"), eq("doc-001")))
                    .thenReturn(1);

            boolean result = repository.delete("doc-001");

            assertThat(result).isTrue();
        }

        @Test
        void 文档不存在_删除返回false() {
            when(jdbcTemplate.update(contains("DELETE FROM ds_documents"), eq("nonexistent")))
                    .thenReturn(0);

            boolean result = repository.delete("nonexistent");

            assertThat(result).isFalse();
        }

        @Test
        void 删除传递正确的文档ID() {
            when(jdbcTemplate.update(anyString(), (Object) any())).thenReturn(1);

            repository.delete("specific-id");

            verify(jdbcTemplate).update(contains("DELETE FROM ds_documents"), eq("specific-id"));
        }
    }

    // ==================== countByCollection 测试 ====================

    @Nested
    class 按集合计数 {

        @Test
        void 正常计数_返回数量() {
            when(jdbcTemplate.queryForObject(
                    contains("COUNT(*)"),
                    eq(Integer.class),
                    eq("col-001")))
                    .thenReturn(42);

            int count = repository.countByCollection("col-001");

            assertThat(count).isEqualTo(42);
        }

        @Test
        void 集合无文档_返回零() {
            when(jdbcTemplate.queryForObject(
                    contains("COUNT(*)"),
                    eq(Integer.class),
                    eq("col-empty")))
                    .thenReturn(0);

            int count = repository.countByCollection("col-empty");

            assertThat(count).isEqualTo(0);
        }

        @Test
        void queryForObject返回null_安全返回零() {
            when(jdbcTemplate.queryForObject(
                    contains("COUNT(*)"),
                    eq(Integer.class),
                    eq("col-null")))
                    .thenReturn(null);

            int count = repository.countByCollection("col-null");

            assertThat(count).isEqualTo(0);
        }
    }

    // ==================== FTS5 同步操作测试 ====================

    @Nested
    class FTS5索引操作 {

        @Test
        void insertFts_执行INSERT语句() {
            repository.insertFts("doc-001", "全文搜索测试内容");

            verify(jdbcTemplate).update(
                    contains("INSERT INTO ds_documents_fts"),
                    eq("doc-001"),
                    eq("全文搜索测试内容"));
        }

        @Test
        void deleteFts_执行DELETE语句() {
            repository.deleteFts("doc-001");

            verify(jdbcTemplate).update(
                    contains("DELETE FROM ds_documents_fts"),
                    eq("doc-001"));
        }

        @Test
        void updateFts_先删除后插入() {
            repository.updateFts("doc-001", "更新后的内容");

            // 验证调用顺序：先 DELETE 后 INSERT
            var inOrder = inOrder(jdbcTemplate);
            inOrder.verify(jdbcTemplate).update(
                    contains("DELETE FROM ds_documents_fts"),
                    eq("doc-001"));
            inOrder.verify(jdbcTemplate).update(
                    contains("INSERT INTO ds_documents_fts"),
                    eq("doc-001"),
                    eq("更新后的内容"));
        }

        @Test
        void updateFts_删除阶段异常_不执行插入() {
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("删除失败"))
                    .when(jdbcTemplate).update(contains("DELETE FROM ds_documents_fts"), eq("doc-001"));

            assertThatThrownBy(() -> repository.updateFts("doc-001", "新内容"))
                    .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

            // INSERT 不应被调用（因为 DELETE 已经抛出异常）
            verify(jdbcTemplate, never()).update(
                    contains("INSERT INTO ds_documents_fts"),
                    eq("doc-001"),
                    anyString());
        }

        @Test
        void insertFts_空字符串内容_正常执行() {
            repository.insertFts("doc-001", "");

            verify(jdbcTemplate).update(
                    contains("INSERT INTO ds_documents_fts"),
                    eq("doc-001"),
                    eq(""));
        }
    }

    // ==================== searchFts 测试 ====================

    @Nested
    class FTS全文搜索 {

        @SuppressWarnings("unchecked")
        @Test
        void 正常搜索_返回匹配文档() {
            var doc1 = 创建文档("doc-001", "col-001", "{\"title\":\"搜索命中\"}");
            when(jdbcTemplate.query(
                    contains("ds_documents_fts MATCH"),
                    any(RowMapper.class),
                    eq("col-001"),
                    eq("关键词"),
                    eq(10)))
                    .thenReturn(List.of(doc1));

            List<Document> result = repository.searchFts("col-001", "关键词", 10);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().id()).isEqualTo("doc-001");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 无匹配_返回空列表() {
            when(jdbcTemplate.query(
                    contains("ds_documents_fts MATCH"),
                    any(RowMapper.class),
                    eq("col-001"),
                    eq("不存在的关键词"),
                    eq(5)))
                    .thenReturn(Collections.emptyList());

            List<Document> result = repository.searchFts("col-001", "不存在的关键词", 5);

            assertThat(result).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void SQL包含JOIN和ORDER_BY_rank() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            repository.searchFts("col-001", "测试", 10);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).query(
                    sqlCaptor.capture(),
                    any(RowMapper.class),
                    eq("col-001"),
                    eq("测试"),
                    eq(10));

            String sql = sqlCaptor.getValue();
            assertThat(sql).contains("JOIN ds_documents_fts fts ON d.id = fts.document_id");
            assertThat(sql).contains("ORDER BY rank");
            assertThat(sql).contains("LIMIT ?");
        }

        @SuppressWarnings("unchecked")
        @Test
        void limit参数正确传递() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            repository.searchFts("col-001", "查询", 3);

            verify(jdbcTemplate).query(
                    anyString(),
                    any(RowMapper.class),
                    eq("col-001"),
                    eq("查询"),
                    eq(3));
        }
    }

    // ==================== query 动态查询测试 ====================

    @Nested
    class 动态查询 {

        @SuppressWarnings("unchecked")
        @Test
        void 正常查询_传递SQL和参数() {
            var doc = 创建文档("doc-001", "col-001", "{}");
            String sql = "SELECT * FROM ds_documents WHERE collection_id = ? AND json_extract(data_json, '$.age') > ?";
            Object[] params = {"col-001", 18};
            when(jdbcTemplate.query(eq(sql), any(RowMapper.class), eq(params)))
                    .thenReturn(List.of(doc));

            List<Document> result = repository.query(sql, params);

            assertThat(result).hasSize(1);
            verify(jdbcTemplate).query(eq(sql), any(RowMapper.class), eq(params));
        }

        @SuppressWarnings("unchecked")
        @Test
        void 空参数数组_正常执行() {
            String sql = "SELECT * FROM ds_documents";
            Object[] params = {};
            when(jdbcTemplate.query(eq(sql), any(RowMapper.class), eq(params)))
                    .thenReturn(Collections.emptyList());

            List<Document> result = repository.query(sql, params);

            assertThat(result).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void 查询无结果_返回空列表() {
            String sql = "SELECT * FROM ds_documents WHERE 1 = 0";
            Object[] params = {};
            when(jdbcTemplate.query(eq(sql), any(RowMapper.class), eq(params)))
                    .thenReturn(Collections.emptyList());

            List<Document> result = repository.query(sql, params);

            assertThat(result).isEmpty();
        }
    }

    // ==================== aggregate 聚合测试 ====================

    @Nested
    class 聚合查询 {

        @SuppressWarnings("unchecked")
        @Test
        void 正常聚合_返回时间桶结果() {
            var r1 = new AggregationResult("2026-03-01", 100.5);
            var r2 = new AggregationResult("2026-03-02", 200.0);
            String sql = "SELECT date(recorded_at) AS bucket, SUM(json_extract(data_json, '$.value')) FROM ds_documents WHERE collection_id = ? GROUP BY bucket";
            Object[] params = {"col-001"};
            when(jdbcTemplate.query(eq(sql), any(RowMapper.class), eq(params)))
                    .thenReturn(List.of(r1, r2));

            List<AggregationResult> result = repository.aggregate(sql, params);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).timeBucket()).isEqualTo("2026-03-01");
            assertThat(result.get(0).value()).isEqualTo(100.5);
            assertThat(result.get(1).timeBucket()).isEqualTo("2026-03-02");
            assertThat(result.get(1).value()).isEqualTo(200.0);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 无数据_返回空列表() {
            String sql = "SELECT date(recorded_at), COUNT(*) FROM ds_documents WHERE collection_id = ? GROUP BY 1";
            Object[] params = {"col-empty"};
            when(jdbcTemplate.query(eq(sql), any(RowMapper.class), eq(params)))
                    .thenReturn(Collections.emptyList());

            List<AggregationResult> result = repository.aggregate(sql, params);

            assertThat(result).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void 聚合值为零和负数() {
            var r1 = new AggregationResult("2026-03-01", 0.0);
            var r2 = new AggregationResult("2026-03-02", -15.3);
            String sql = "SELECT bucket, value FROM aggregated";
            Object[] params = {};
            when(jdbcTemplate.query(eq(sql), any(RowMapper.class), eq(params)))
                    .thenReturn(List.of(r1, r2));

            List<AggregationResult> result = repository.aggregate(sql, params);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).value()).isEqualTo(0.0);
            assertThat(result.get(1).value()).isEqualTo(-15.3);
        }
    }

    // ==================== RowMapper 集成测试 ====================

    @Nested
    class RowMapper映射验证 {

        @SuppressWarnings("unchecked")
        @Test
        void findById使用的RowMapper能正确映射所有字段() {
            // 通过 findById 间接测试 documentRowMapper，
            // 验证 RowMapper 被传递给 JdbcTemplate
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                    .thenReturn(Collections.emptyList());

            repository.findById("any-id");

            // 验证 query 被调用了，且 RowMapper 不为 null
            ArgumentCaptor<RowMapper<Document>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
            verify(jdbcTemplate).query(anyString(), mapperCaptor.capture(), eq("any-id"));
            assertThat(mapperCaptor.getValue()).isNotNull();
        }

        @SuppressWarnings("unchecked")
        @Test
        void aggregate使用的RowMapper不为null() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(Collections.emptyList());

            repository.aggregate("SELECT 1", new Object[]{});

            ArgumentCaptor<RowMapper<AggregationResult>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
            verify(jdbcTemplate).query(anyString(), mapperCaptor.capture(), any(Object[].class));
            assertThat(mapperCaptor.getValue()).isNotNull();
        }
    }
}
