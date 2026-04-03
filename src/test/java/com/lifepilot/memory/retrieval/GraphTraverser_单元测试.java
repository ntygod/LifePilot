package com.lifepilot.memory.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GraphTraverser 图遍历检索器单元测试。
 * <p>
 * 覆盖场景：空输入、无起始实体、单跳遍历、多跳遍历、深度评分、limit 限制、异常兜底。
 * </p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class GraphTraverser_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private GraphTraverser traverser;

    @BeforeEach
    void 初始化() {
        traverser = new GraphTraverser(jdbcTemplate);
    }

    // ==================== 空输入 / 边界场景 ====================

    @Nested
    class 空输入与边界场景 {

        @Test
        void query为null时返回空列表() {
            var result = traverser.traverse(null, 10);

            assertTrue(result.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void query为空字符串时返回空列表() {
            var result = traverser.traverse("", 10);

            assertTrue(result.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        void query为空白字符串时返回空列表() {
            var result = traverser.traverse("   \t\n  ", 10);

            assertTrue(result.isEmpty());
            verifyNoInteractions(jdbcTemplate);
        }
    }

    // ==================== 无起始实体 ====================

    @Nested
    class 无起始实体场景 {

        @Test
        void 未匹配到任何起始实体时返回空列表() {
            // given
            mockFindStartEntities(List.of());

            // when
            var result = traverser.traverse("不存在的实体名称", 10);

            // then
            assertTrue(result.isEmpty());
            verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class), any());
        }
    }

    // ==================== 单跳遍历 ====================

    @Nested
    class 单跳遍历场景 {

        @Test
        void 单跳关联实体返回得分1点0() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            var now = Instant.parse("2026-04-01T00:00:00Z");
            var expected = new RankedItem(
                    "entity-hop1", "CONCEPT", "关联概念A", "描述A",
                    1.0f, now, 0.8f, null, now);

            mockCteQuery(List.of(expected));

            // when
            var result = traverser.traverse("包含起始实体的查询", 10);

            // then
            assertEquals(1, result.size());
            var item = result.getFirst();
            assertEquals("entity-hop1", item.entityId());
            assertEquals("CONCEPT", item.entityType());
            assertEquals("关联概念A", item.name());
            assertEquals(1.0f, item.score(), 0.001f);
            assertEquals(0.8f, item.importanceScore(), 0.001f);
            assertEquals(now, item.lastAccessedAt());
            assertEquals(now, item.updatedAt());
        }

        @Test
        void 单跳返回多个关联实体() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            var itemA = rankedItem("entity-a", "PERSON", "张三", 1.0f);
            var itemB = rankedItem("entity-b", "LOCATION", "北京", 1.0f);
            mockCteQuery(List.of(itemA, itemB));

            // when
            var result = traverser.traverse("测试查询张三", 10);

            // then
            assertEquals(2, result.size());
            assertEquals("entity-a", result.get(0).entityId());
            assertEquals("entity-b", result.get(1).entityId());
        }
    }

    // ==================== 多跳遍历（深度评分） ====================

    @Nested
    class 多跳遍历与深度评分 {

        @Test
        void 二跳关联实体返回得分0点5() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            var hop2Item = rankedItem("entity-hop2", "EVENT", "某事件", 0.5f);
            mockCteQuery(List.of(hop2Item));

            // when
            var result = traverser.traverse("包含起始实体的查询", 10);

            // then
            assertEquals(1, result.size());
            assertEquals(0.5f, result.getFirst().score(), 0.001f);
        }

        @Test
        void 混合深度结果按深度排序_一跳排在二跳前面() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            var hop1Item = rankedItem("entity-hop1", "PERSON", "直接关联人", 1.0f);
            var hop2Item = rankedItem("entity-hop2", "CONCEPT", "间接关联概念", 0.5f);
            mockCteQuery(List.of(hop1Item, hop2Item));

            // when
            var result = traverser.traverse("包含起始实体的查询", 10);

            // then
            assertEquals(2, result.size());
            assertEquals(1.0f, result.get(0).score(), 0.001f);
            assertEquals(0.5f, result.get(1).score(), 0.001f);
            assertEquals("entity-hop1", result.get(0).entityId());
            assertEquals("entity-hop2", result.get(1).entityId());
        }
    }

    // ==================== 无关系时返回空结果 ====================

    @Nested
    class 无关系场景 {

        @Test
        void 起始实体存在但无任何关系时返回空列表() {
            // given
            mockFindStartEntities(List.of("entity-isolated"));
            mockCteQuery(List.of());

            // when
            var result = traverser.traverse("孤立实体查询", 10);

            // then
            assertTrue(result.isEmpty());
        }
    }

    // ==================== limit 参数限制 ====================

    @Nested
    class limit参数场景 {

        @Test
        void limit为1时只返回最近一跳的单个结果() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            var singleItem = rankedItem("entity-top1", "PERSON", "最相关的人", 1.0f);
            mockCteQuery(List.of(singleItem));

            // when
            var result = traverser.traverse("查询实体", 1);

            // then
            assertEquals(1, result.size());
            verifyCteQueryCalledWithTopK(1);
        }

        @Test
        void limit为0时查询仍然执行_由数据库决定返回结果() {
            // given
            mockFindStartEntities(List.of("entity-start"));
            mockCteQuery(List.of());

            // when
            var result = traverser.traverse("查询实体", 0);

            // then
            assertTrue(result.isEmpty());
            verifyCteQueryCalledWithTopK(0);
        }

        @Test
        void topK参数正确传递到CTE查询的LIMIT子句() {
            // given
            mockFindStartEntities(List.of("entity-start"));
            mockCteQuery(List.of());

            // when
            traverser.traverse("查询实体", 25);

            // then
            verifyCteQueryCalledWithTopK(25);
        }
    }

    // ==================== 起始实体选择逻辑 ====================

    @Nested
    class 起始实体选择 {

        @Test
        void 多个匹配实体时使用第一个() {
            // given
            mockFindStartEntities(List.of("entity-longest-name", "entity-short"));
            mockCteQuery(List.of());

            // when
            traverser.traverse("包含实体名的查询", 10);

            // then
            verifyCteQueryCalledWithEntityId("entity-longest-name");
        }
    }

    // ==================== 异常兜底 ====================

    @Nested
    class 异常处理 {

        @Test
        @SuppressWarnings("unchecked")
        void CTE查询抛出异常时返回空列表而非传播异常() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            when(jdbcTemplate.query(
                    contains("WITH RECURSIVE"),
                    any(RowMapper.class),
                    any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("SQLite 查询超时"));

            // when
            var result = traverser.traverse("查询实体", 10);

            // then
            assertTrue(result.isEmpty());
        }
    }

    // ==================== Nullable 字段处理 ====================

    @Nested
    class 可空字段处理 {

        @Test
        void lastAccessedAt和updatedAt为null时不抛异常() {
            // given
            mockFindStartEntities(List.of("entity-start"));

            var itemWithNulls = new RankedItem(
                    "entity-1", "CONCEPT", "概念", "描述",
                    1.0f, null, 0.5f, null, null);
            mockCteQuery(List.of(itemWithNulls));

            // when
            var result = traverser.traverse("查询实体", 10);

            // then
            assertEquals(1, result.size());
            assertNull(result.getFirst().lastAccessedAt());
            assertNull(result.getFirst().updatedAt());
        }
    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private void mockFindStartEntities(List<String> entityIds) {
        when(jdbcTemplate.query(
                contains("temporal_entities WHERE is_current"),
                any(RowMapper.class),
                any()))
                .thenReturn(entityIds);
    }

    @SuppressWarnings("unchecked")
    private void mockCteQuery(List<RankedItem> items) {
        when(jdbcTemplate.query(
                contains("WITH RECURSIVE"),
                any(RowMapper.class),
                any(), any(), any(), any(), any()))
                .thenReturn(items);
    }

    @SuppressWarnings("unchecked")
    private void verifyCteQueryCalledWithTopK(int expectedTopK) {
        verify(jdbcTemplate).query(
                contains("WITH RECURSIVE"),
                any(RowMapper.class),
                any(), any(), any(), any(), eq(expectedTopK));
    }

    @SuppressWarnings("unchecked")
    private void verifyCteQueryCalledWithEntityId(String expectedEntityId) {
        verify(jdbcTemplate).query(
                contains("WITH RECURSIVE"),
                any(RowMapper.class),
                eq(expectedEntityId), eq(expectedEntityId),
                eq(expectedEntityId), eq(expectedEntityId), anyInt());
    }

    private RankedItem rankedItem(String entityId, String type, String name, float score) {
        return new RankedItem(entityId, type, name, null, score, null, 0.5f, null, null);
    }
}
