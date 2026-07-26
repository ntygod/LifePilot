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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VectorSearcher 单元测试 — 覆盖构造初始化、searchEntities sqlite-vec 正常路径、
 * 阈值过滤、空结果、失败暴露、upsertEntityVector 先删后插、deleteEntityVector 正常删除、
 * topK 参数传递以及边界条件。
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
        void vec扩展未加载时进入降级模式且不建表() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);

            assertThat(searcher.isVecExtensionLoaded()).isFalse();
            verify(vectorJdbcTemplate, never()).execute(anyString());
        }

        @Test
        void vec0表创建失败时构造失败() {
            doThrow(new RuntimeException("sqlite-vec 扩展不可用"))
                    .when(vectorJdbcTemplate).execute(anyString());

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("sqlite-vec 扩展不可用");
        }

        @Test
        void embeddingRouter缺失时构造失败() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new VectorSearcher(vectorJdbcTemplate, null, true, DIMENSIONS))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("embeddingRouter 不能为空");
        }

        @Test
        void embeddingDimensions非法时构造失败() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("embeddingDimensions 必须大于 0");
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
    //  降级模式
    // ═════════════════════════════════════════════════

    @Nested
    class 向量能力降级 {

        private VectorSearcher searcher;

        @BeforeEach
        void 初始化() {
            searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, false, DIMENSIONS);
            clearInvocations(vectorJdbcTemplate, embeddingRouter);
        }

        @Test
        void 搜索返回空结果且不请求Embedding() {
            var results = searcher.searchEntities("测试查询", 5, 0.5f);

            assertThat(results).isEmpty();
            verifyNoInteractions(embeddingRouter);
            verifyNoInteractions(vectorJdbcTemplate);
        }

        @Test
        void 带候选集搜索返回空结果且不访问向量库() {
            var results = searcher.searchEntities("测试查询", 5, 0.5f, Set.of("entity-1"));

            assertThat(results).isEmpty();
            verifyNoInteractions(embeddingRouter);
            verifyNoInteractions(vectorJdbcTemplate);
        }

        @Test
        void 写入和删除实体向量时跳过向量库() {
            searcher.upsertEntityVector("entity-1", "测试文本");
            searcher.deleteEntityVector("entity-1");

            verifyNoInteractions(embeddingRouter);
            verifyNoInteractions(vectorJdbcTemplate);
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
    //  searchEntities — 失败暴露
    // ═════════════════════════════════════════════════

    @Nested
    class 向量搜索_失败暴露 {

        @Test
        void embedding异常时应抛出且不查询数据库() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), any(), any()))
                    .thenThrow(new RuntimeException("向量服务不可用"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("测试", 5, 0.5f))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("向量服务不可用");

            // execute 在构造器中被调用（创建表），但 query 不应被调用
            verify(vectorJdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(), anyInt());
        }

        @Test
        void vec搜索异常时应抛出() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("查询失败", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));

            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenThrow(new RuntimeException("vec 查询失败"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("查询失败", 5, 0.5f))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("vec 查询失败");
        }

        @Test
        void embedding返回null时应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("坏向量", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(null);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("坏向量", 5, 0.5f))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("向量检索查询返回 null 向量");
        }

        @Test
        void embedding返回维度不匹配时应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("坏维度", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(new float[]{1.0f, 2.0f});

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("坏维度", 5, 0.5f))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("向量检索查询返回向量维度不匹配");
        }

        @Test
        void embedding返回NaN时应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("坏数值", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(new float[]{1.0f, Float.NaN, 1.0f, 1.0f});

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("坏数值", 5, 0.5f))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("向量检索查询返回非法向量值");
        }

        @SuppressWarnings("unchecked")
        @Test
        void vec查询返回null时应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("null结果", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(null);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("null结果", 5, 0.5f))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("向量检索 SQL 查询返回 null");
        }

        @SuppressWarnings("unchecked")
        @Test
        void vec查询返回null元素时应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("null元素", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(1.0f));
            when(vectorJdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(java.util.Arrays.asList(new VectorSearchResult("ok", 0.9f), null));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("null元素", 5, 0.5f))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("向量检索 SQL 查询返回 null 元素");
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
        void embedding失败时应抛出且不写库() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed(anyString(), any(), any(), any()))
                    .thenThrow(new RuntimeException("向量化失败"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.upsertEntityVector("entity-1", "实体文本"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("向量化失败");

            // DELETE 和 INSERT 都不应被调用
            verify(vectorJdbcTemplate, never()).update(eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), (Object) any());
            verify(vectorJdbcTemplate, never()).update(eq("INSERT INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)"), (Object) any(), any());
        }

        @Test
        void 数据库DELETE失败时应抛出() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("文本", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(0.5f));
            when(vectorJdbcTemplate.update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), eq("entity-1")))
                    .thenThrow(new RuntimeException("磁盘已满"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.upsertEntityVector("entity-1", "文本"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("磁盘已满");
        }

        @Test
        void 数据库INSERT失败时应抛出() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(embeddingRouter.embed("文本", EmbeddingUseCase.MEMORY, null, null))
                    .thenReturn(uniformVector(0.5f));
            when(vectorJdbcTemplate.update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), eq("entity-1")))
                    .thenReturn(1);
            when(vectorJdbcTemplate.update(
                    eq("INSERT INTO entity_embeddings(entity_id, embedding) VALUES(?, ?)"),
                    eq("entity-1"), any(byte[].class)))
                    .thenThrow(new RuntimeException("INSERT 失败"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.upsertEntityVector("entity-1", "文本"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("INSERT 失败");
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
        void 删除失败时应抛出() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);
            when(vectorJdbcTemplate.update(
                    eq("DELETE FROM entity_embeddings WHERE entity_id = ?"), eq("entity-1")))
                    .thenThrow(new RuntimeException("表不存在"));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.deleteEntityVector("entity-1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("表不存在");
        }
    }

    // ═════════════════════════════════════════════════
    //  边界条件
    // ═════════════════════════════════════════════════

    @Nested
    class 边界条件 {

        @Test
        void 非法查询参数应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.searchEntities(" ", 1, 0.5f))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("向量检索查询文本不能为空");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.searchEntities("query", 0, 0.5f))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("topK 必须大于 0");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.searchEntities("query", 1, -0.1f))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threshold 必须在 [0,1] 范围内");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.searchEntities("query", 1, Float.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("threshold 必须在 [0,1] 范围内");
        }

        @Test
        void 候选ID包含空值时应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    searcher.searchEntities("query", 5, 0.5f, Set.of("ok", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entityId 不能为空");
        }

        @Test
        void 空候选ID集合返回空且不调用embedding() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            var results = searcher.searchEntities("query", 5, 0.5f, Set.of());

            assertThat(results).isEmpty();
            verify(embeddingRouter, never()).embed(anyString(), any(), any(), any());
        }

        @Test
        void 写入和删除遇到非法entityId应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.upsertEntityVector(" ", "文本"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entityId 不能为空");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.deleteEntityVector(" entity "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entityId 不能包含首尾空白");
        }

        @Test
        void 写入遇到空文本应直接失败() {
            var searcher = new VectorSearcher(vectorJdbcTemplate, embeddingRouter, true, DIMENSIONS);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> searcher.upsertEntityVector("entity-1", " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("向量写入文本不能为空");
        }

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
            @ForAll("合法文本") String queryText,
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
     * 属性测试：embedding 随机异常时 searchEntities 直接暴露失败。
     */
    @Property(tries = 100)
    void embedding异常时searchEntities抛出(
            @ForAll("合法文本") String queryText) {

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        EmbeddingRouter mockRouter = mock(EmbeddingRouter.class);
        when(mockRouter.embed(anyString(), any(), isNull(), isNull()))
                .thenThrow(new RuntimeException("随机向量化失败"));

        var searcher = new VectorSearcher(mockJdbc, mockRouter, true, DIMENSIONS);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                searcher.searchEntities(queryText, 5, 0.5f))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("随机向量化失败");
    }

    /**
     * 属性测试：upsertEntityVector 在任意合法 entityId 和 text 下永不抛异常。
     */
    @Property(tries = 100)
    void upsertEntityVector对任意合法输入永不抛异常(
            @ForAll("合法ID") String entityId,
            @ForAll("合法文本") String text) {

        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        EmbeddingRouter mockRouter = mock(EmbeddingRouter.class);
        when(mockRouter.embed(anyString(), any(), isNull(), isNull()))
                .thenReturn(uniformVector(0.5f));

        var searcher = new VectorSearcher(mockJdbc, mockRouter, true, DIMENSIONS);

        assertThatCode(() -> searcher.upsertEntityVector(entityId, text))
                .doesNotThrowAnyException();
    }

    @Property(tries = 50)
    void 非法相似度构造VectorSearchResult失败(@ForAll("非法相似度") float similarity) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new VectorSearchResult("entity", similarity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("similarity 必须在 [0,1] 范围内");
    }

    @Test
    void entityId含首尾空白时构造VectorSearchResult失败() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new VectorSearchResult(" entity ", 0.9f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId 不能包含首尾空白");
    }

    @Provide("合法文本")
    Arbitrary<String> 合法文本() {
        var chineseChars = Arbitraries.chars().range('\u4e00', '\u9fff');
        var asciiChars = Arbitraries.chars().alpha();
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

    @Provide("合法ID")
    Arbitrary<String> 合法ID() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Provide("非法相似度")
    Arbitrary<Float> 非法相似度() {
        return Arbitraries.of(
                -0.001f,
                -1.0f,
                1.001f,
                2.0f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY);
    }
}
