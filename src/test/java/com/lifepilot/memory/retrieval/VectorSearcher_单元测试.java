package com.lifepilot.memory.retrieval;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import net.jqwik.api.*;
import net.jqwik.api.constraints.FloatRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VectorSearcher 单元测试 — 覆盖构造初始化（vec0 表创建/降级）、searchEntities（sqlite-vec 正常路径、
 * 阈值过滤、空结果、vec 异常降级 JVM、embedding 异常返回空）、upsertEntityVector（先删后插、扩展未加载跳过、
 * embedding 失败不写库）、deleteEntityVector（正常删除、扩展未加载跳过、删除失败不抛异常）、
 * 结果排序/topK 截断、以及边界条件。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class VectorSearcher_单元测试 {

    @Mock
    private JdbcTemplate vectorJdbcTemplate;

    @Mock
    private EmbeddingRouter embeddingRouter;

    /** 测试用向量维度 */
    private static final int DIMENSIONS = 4;

    /** 生成一个均匀的 float 向量 */
    private static float[] uniformVector(float value) {
        return new float[]{value, value, value, value};
    }

    /** float[] 转小端序 byte[]，与 VectorSearcher 内部格式一致 */
    private static byte[] toBytes(float[] floats) {
        var buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    // ═════════════════════════════════════════════════
    //  构造器初始化
    // ═════════════════════════════════════════════════

    @Nested
    class 构造与初始化 {

        @Test
        void vec扩展已加载时创建vec0虚拟表() {
            new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            verify(vectorJdbcTemplate).execute(contains("CREATE VIRTUAL TABLE IF NOT EXISTS entity_embeddings"));
        }

        @Test
        void vec扩展已加载时DDL包含正确维度() {
            new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, 768);

            verify(vectorJdbcTemplate).execute(contains("FLOAT[768]"));
        }

        @Test
        void vec扩展未加载时跳过表创建() {
            new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);

            verify(vectorJdbcTemplate, never()).execute(anyString());
        }

        @Test
        void vec0表创建失败时不抛出异常_降级运行() {
            doThrow(new RuntimeException("sqlite-vec 扩展不可用"))
                    .when(vectorJdbcTemplate).execute(anyString());

            assertThatCode(() ->
                    new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS)
            ).doesNotThrowAnyException();
        }

        @Test
        void vec0表创建失败后isVecExtensionLoaded仍返回true() {
            // vecExtensionLoaded 是构造参数，不因 DDL 失败而改变
            doThrow(new RuntimeException("DDL 失败"))
                    .when(vectorJdbcTemplate).execute(anyString());

            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            assertThat(searcher.isVecExtensionLoaded()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════
    //  isVecExtensionLoaded
    // ═════════════════════════════════════════════════

    @Nested
    class 扩展状态查询 {

        @Test
        void 扩展已加载返回true() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            assertThat(searcher.isVecExtensionLoaded()).isTrue();
        }

        @Test
        void 扩展未加载返回false() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);

            assertThat(searcher.isVecExtensionLoaded()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════
    //  searchEntities — sqlite-vec 正常路径
    // ═════════════════════════════════════════════════

    @Nested
    class 向量搜索_sqlite_vec正常路径 {

        private VectorSearcher searcher;

        @BeforeEach
        void 初始化() {
            searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 正常查询返回结果列表() {
            float[] queryVector = uniformVector(1.0f);
            when(embeddingRouter.embed("测试查询", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(queryVector);

            var expected = List.of(
                    new VectorSearchResult("entity-1", 0.95f),
                    new VectorSearchResult("entity-2", 0.80f)
            );
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(expected);

            var results = searcher.searchEntities("测试查询", 5, 0.5f);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).entityId()).isEqualTo("entity-1");
            assertThat(results.get(0).similarity()).isEqualTo(0.95f);
            assertThat(results.get(1).entityId()).isEqualTo("entity-2");
            assertThat(results.get(1).similarity()).isEqualTo(0.80f);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 相似度低于阈值的结果被过滤() {
            when(embeddingRouter.embed("查询", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            var rawResults = List.of(
                    new VectorSearchResult("above", 0.9f),
                    new VectorSearchResult("below", 0.3f)
            );
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(rawResults);

            var results = searcher.searchEntities("查询", 10, 0.5f);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().entityId()).isEqualTo("above");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 所有结果低于阈值时返回空列表() {
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(List.of(
                            new VectorSearchResult("e1", 0.2f),
                            new VectorSearchResult("e2", 0.1f)
                    ));

            var results = searcher.searchEntities("高阈值", 10, 0.8f);

            assertThat(results).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void 数据库无匹配时返回空列表() {
            when(embeddingRouter.embed("空结果", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(List.of());

            var results = searcher.searchEntities("空结果", 5, 0.5f);

            assertThat(results).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void topK参数正确传递给SQL查询() {
            when(embeddingRouter.embed("topK测试", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), eq(3)))
                    .thenReturn(List.of(new VectorSearchResult("e1", 0.9f)));

            searcher.searchEntities("topK测试", 3, 0.0f);

            verify(vectorJdbcTemplate).query(anyString(), any(RowMapper.class), any(), eq(3));
        }
    }

    // ═════════════════════════════════════════════════
    //  searchEntities — 降级和异常处理
    // ═════════════════════════════════════════════════

    @Nested
    class 向量搜索_降级与异常 {

        @Test
        void embedding异常时返回空列表_不查询数据库() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), any(), any()))
                    .thenThrow(new RuntimeException("向量服务不可用"));

            var results = searcher.searchEntities("测试", 5, 0.5f);

            assertThat(results).isEmpty();
            // execute 在构造器中被调用（创建表），但 query 不应被调用
            verify(vectorJdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), anyInt());
        }

        @SuppressWarnings("unchecked")
        @Test
        void vec搜索异常时降级为JVM暴力搜索() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("降级测试", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            // vec KNN 搜索抛异常
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new RuntimeException("vec 查询失败"));
            // JVM fallback 返回结果
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenReturn(List.of(new VectorSearchResult("fallback-1", 0.85f)));

            var results = searcher.searchEntities("降级测试", 5, 0.5f);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().entityId()).isEqualTo("fallback-1");
        }

        @SuppressWarnings("unchecked")
        @Test
        void vec搜索和JVM暴力搜索都失败时返回空列表() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("双重失败", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new RuntimeException("vec 查询失败"));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenThrow(new RuntimeException("JVM 暴力搜索也失败"));

            var results = searcher.searchEntities("双重失败", 5, 0.5f);

            assertThat(results).isEmpty();
        }

        @Test
        void vec扩展未加载时JVM暴力搜索直接返回空列表() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);
            when(embeddingRouter.embed("无向量数据", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            var results = searcher.searchEntities("无向量数据", 5, 0.5f);

            assertThat(results).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void DataAccessException也能触发降级() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new DataAccessException("连接池耗尽") {});
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenReturn(List.of(new VectorSearchResult("recovered", 0.75f)));

            var results = searcher.searchEntities("DA异常", 5, 0.5f);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().entityId()).isEqualTo("recovered");
        }
    }

    // ═════════════════════════════════════════════════
    //  searchEntities — JVM 暴力搜索排序与截断
    // ═════════════════════════════════════════════════

    @Nested
    class JVM暴力搜索排序与截断 {

        @SuppressWarnings("unchecked")
        @Test
        void 结果按相似度降序排列() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("排序测试", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            // 强制降级
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new RuntimeException("强制降级"));
            // 乱序结果
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenReturn(List.of(
                            new VectorSearchResult("low", 0.5f),
                            new VectorSearchResult("high", 0.9f),
                            new VectorSearchResult("mid", 0.7f)
                    ));

            var results = searcher.searchEntities("排序测试", 10, 0.0f);

            assertThat(results).extracting(VectorSearchResult::entityId)
                    .containsExactly("high", "mid", "low");
            assertThat(results).extracting(VectorSearchResult::similarity)
                    .isSortedAccordingTo((a, b) -> Float.compare(b, a));
        }

        @SuppressWarnings("unchecked")
        @Test
        void topK限制截断结果() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("topK降级", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new RuntimeException("强制降级"));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenReturn(List.of(
                            new VectorSearchResult("e1", 0.95f),
                            new VectorSearchResult("e2", 0.90f),
                            new VectorSearchResult("e3", 0.85f),
                            new VectorSearchResult("e4", 0.80f)
                    ));

            var results = searcher.searchEntities("topK降级", 2, 0.0f);

            assertThat(results).hasSize(2);
            assertThat(results).extracting(VectorSearchResult::entityId)
                    .containsExactly("e1", "e2");
        }

        @SuppressWarnings("unchecked")
        @Test
        void 阈值过滤与topK截断组合生效() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("组合过滤", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new RuntimeException("强制降级"));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenReturn(List.of(
                            new VectorSearchResult("above-1", 0.9f),
                            new VectorSearchResult("above-2", 0.8f),
                            new VectorSearchResult("above-3", 0.7f),
                            new VectorSearchResult("below", 0.3f)
                    ));

            // 阈值 0.5 过滤掉 below(0.3)，topK 2 取前 2 条
            var results = searcher.searchEntities("组合过滤", 2, 0.5f);

            assertThat(results).hasSize(2);
            assertThat(results).extracting(VectorSearchResult::entityId)
                    .containsExactly("above-1", "above-2");
        }
    }

    // ═════════════════════════════════════════════════
    //  upsertEntityVector
    // ═════════════════════════════════════════════════

    @Nested
    class 更新实体向量 {

        @Test
        void 正常流程_先删后插_顺序正确() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("实体文本", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(0.5f));

            searcher.upsertEntityVector("entity-1", "实体文本");

            InOrder inOrder = inOrder(vectorJdbcTemplate);
            inOrder.verify(vectorJdbcTemplate).update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"),
                    eq("entity-1"));
            inOrder.verify(vectorJdbcTemplate).update(
                    eq("INSERT INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)"),
                    eq("entity-1"),
                    any(byte[].class));
        }

        @Test
        void 扩展未加载时跳过_不调用embedding和数据库() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);

            searcher.upsertEntityVector("entity-1", "实体文本");

            verifyNoInteractions(embeddingRouter);
            verify(vectorJdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        void embedding失败时不写库_不抛异常() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), any(), any()))
                    .thenThrow(new RuntimeException("向量化失败"));

            assertThatCode(() ->
                    searcher.upsertEntityVector("entity-1", "实体文本")
            ).doesNotThrowAnyException();

            // DELETE 和 INSERT 都不应被调用
            verify(vectorJdbcTemplate, never()).update(eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), (Object) any());
            verify(vectorJdbcTemplate, never()).update(eq("INSERT INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)"), (Object) any(), any());
        }

        @Test
        void 数据库DELETE失败时整体不抛异常() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("文本", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(0.5f));
            when(vectorJdbcTemplate.update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), eq("entity-1")))
                    .thenThrow(new DataAccessException("磁盘已满") {});

            assertThatCode(() ->
                    searcher.upsertEntityVector("entity-1", "文本")
            ).doesNotThrowAnyException();
        }

        @Test
        void 数据库INSERT失败时不抛异常() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("文本", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(0.5f));
            when(vectorJdbcTemplate.update(
                    eq("INSERT INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)"),
                    eq("entity-1"), any(byte[].class)))
                    .thenThrow(new RuntimeException("INSERT 失败"));

            assertThatCode(() ->
                    searcher.upsertEntityVector("entity-1", "文本")
            ).doesNotThrowAnyException();
        }
    }

    // ═════════════════════════════════════════════════
    //  deleteEntityVector
    // ═════════════════════════════════════════════════

    @Nested
    class 删除实体向量 {

        @Test
        void 正常删除_使用正确SQL和参数() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            searcher.deleteEntityVector("entity-456");

            verify(vectorJdbcTemplate).update(
                    "DELETE FROM entity_embeddings WHERE entity_id = ?",
                    "entity-456");
        }

        @Test
        void 扩展未加载时跳过删除() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);

            searcher.deleteEntityVector("entity-456");

            verify(vectorJdbcTemplate, never()).update(anyString(), any(Object[].class));
        }

        @Test
        void DataAccessException时不抛出() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(vectorJdbcTemplate.update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), eq("entity-1")))
                    .thenThrow(new DataAccessException("表不存在") {});

            assertThatCode(() ->
                    searcher.deleteEntityVector("entity-1")
            ).doesNotThrowAnyException();
        }

        @Test
        void RuntimeException时不抛出() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(vectorJdbcTemplate.update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), eq("entity-1")))
                    .thenThrow(new RuntimeException("未知错误"));

            assertThatCode(() ->
                    searcher.deleteEntityVector("entity-1")
            ).doesNotThrowAnyException();
        }
    }

    // ═════════════════════════════════════════════════
    //  边界条件
    // ═════════════════════════════════════════════════

    @Nested
    class 边界条件 {

        @SuppressWarnings("unchecked")
        @Test
        void 阈值为零时所有结果均通过() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(List.of(
                            new VectorSearchResult("e1", 0.01f),
                            new VectorSearchResult("e2", 0.001f)
                    ));

            var results = searcher.searchEntities("query", 10, 0.0f);

            assertThat(results).hasSize(2);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 阈值为1时仅精确匹配通过() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(List.of(
                            new VectorSearchResult("exact", 1.0f),
                            new VectorSearchResult("near", 0.999f)
                    ));

            var results = searcher.searchEntities("query", 10, 1.0f);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().entityId()).isEqualTo("exact");
        }

        @SuppressWarnings("unchecked")
        @Test
        void topK为1时仅返回一条() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), eq(1)))
                    .thenReturn(List.of(new VectorSearchResult("sole", 0.9f)));

            var results = searcher.searchEntities("query", 1, 0.5f);

            assertThat(results).hasSize(1);
            verify(vectorJdbcTemplate).query(anyString(), any(RowMapper.class), any(), eq(1));
        }

        @SuppressWarnings("unchecked")
        @Test
        void 维度为1的极小向量() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, 1);
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(new float[]{1.0f});
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(List.of(new VectorSearchResult("e1", 0.95f)));

            var results = searcher.searchEntities("query", 5, 0.5f);

            assertThat(results).hasSize(1);
        }

        @SuppressWarnings("unchecked")
        @Test
        void 大topK值正常传递() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), isNull(), isNull()))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), eq(1000)))
                    .thenReturn(List.of());

            searcher.searchEntities("query", 1000, 0.0f);

            verify(vectorJdbcTemplate).query(anyString(), any(RowMapper.class), any(), eq(1000));
        }
    }

    // ═════════════════════════════════════════════════
    //  属性测试
    // ═════════════════════════════════════════════════

    /**
     * 属性测试：searchEntities 在任意合法参数组合下永不抛异常，
     * 总是返回非 null 列表。
     */
    @SuppressWarnings("unchecked")
    @Property(tries = 200)
    void searchEntities对任意参数组合永不抛异常(
            @ForAll("任意查询文本") String queryText,
            @ForAll @IntRange(min = 1, max = 100) int topK,
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float threshold) {

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        EmbeddingRouter mockRouter = mock(EmbeddingRouter.class);
        when(mockRouter.embed(anyString(), any(), isNull(), isNull()))
                .thenReturn(uniformVector(0.5f));
        when(mockJdbc.query(anyString(), any(RowMapper.class), any(), anyInt()))
                .thenReturn(List.of());

        var searcher = new VectorSearcher(mockJdbc, mockRouter, true, DIMENSIONS);

        var results = searcher.searchEntities(queryText, topK, threshold);

        assertThat(results).isNotNull();
    }

    /**
     * 属性测试：embedding 随机异常时 searchEntities 安全返回空列表。
     */
    @Property(tries = 100)
    void embedding异常时searchEntities安全返回空(
            @ForAll("任意查询文本") String queryText) {

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        EmbeddingRouter mockRouter = mock(EmbeddingRouter.class);
        when(mockRouter.embed(anyString(), any(), isNull(), isNull()))
                .thenThrow(new RuntimeException("随机向量化失败"));

        var searcher = new VectorSearcher(mockJdbc, mockRouter, true, DIMENSIONS);

        var results = searcher.searchEntities(queryText, 5, 0.5f);

        assertThat(results).isEmpty();
    }

    /**
     * 属性测试：upsertEntityVector 在任意 entityId 和 text 下永不抛异常。
     */
    @Property(tries = 100)
    void upsertEntityVector对任意输入永不抛异常(
            @ForAll("任意查询文本") String entityId,
            @ForAll("任意查询文本") String text) {

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        EmbeddingRouter mockRouter = mock(EmbeddingRouter.class);
        when(mockRouter.embed(anyString(), any(), isNull(), isNull()))
                .thenReturn(uniformVector(0.5f));

        var searcher = new VectorSearcher(mockJdbc, mockRouter, true, DIMENSIONS);

        assertThatCode(() -> searcher.upsertEntityVector(entityId, text))
                .doesNotThrowAnyException();
    }

    @Provide("任意查询文本")
    Arbitrary<String> 任意查询文本() {
        var chineseChars = Arbitraries.chars().range('\u4e00', '\u9fff');
        var asciiChars = Arbitraries.chars().ascii();
        var mixed = Arbitraries.frequencyOf(
                Tuple.of(2, asciiChars),
                Tuple.of(3, chineseChars));

        return mixed.list().ofMinSize(1).ofMaxSize(50)
                .map(chars -> {
                    var sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }
}
