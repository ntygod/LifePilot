package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.EntityDeduplicator;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EntityDeduplicator 单元测试。
 *
 * @author zsg
 * @since 2026-06-29
 */
@DisplayName("EntityDeduplicator 单元测试")
class EntityDeduplicator_单元测试 {

    @Test
    @DisplayName("向量搜索失败应直接暴露")
    void 向量搜索失败应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var deduplicator = deduplicator(semanticMemory, vectorSearcher, mock(JdbcTemplate.class));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                entity("primary", "主要实体", 0.8f, 3),
                entity("secondary", "重复实体", 0.7f, 1)));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenThrow(new IllegalStateException("向量索引不可用"));

        assertThrows(IllegalStateException.class, deduplicator::dedup);
    }

    @Test
    @DisplayName("向量搜索返回null应直接暴露")
    void 向量搜索返回null应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var deduplicator = deduplicator(semanticMemory, vectorSearcher, mock(JdbcTemplate.class));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                entity("primary", "主要实体", 0.8f, 3),
                entity("secondary", "重复实体", 0.7f, 1)));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenReturn(null);

        assertThrows(IllegalStateException.class, deduplicator::dedup);
    }

    @Test
    @DisplayName("向量搜索返回null候选应直接暴露")
    void 向量搜索返回null候选应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var deduplicator = deduplicator(semanticMemory, vectorSearcher, mock(JdbcTemplate.class));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                entity("primary", "主要实体", 0.8f, 3),
                entity("secondary", "重复实体", 0.7f, 1)));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenReturn(java.util.Collections.singletonList(null));

        assertThrows(IllegalStateException.class, deduplicator::dedup);
    }

    @Test
    @DisplayName("主实体更新命中零行应直接暴露")
    void 主实体更新命中零行应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var deduplicator = deduplicator(semanticMemory, vectorSearcher, jdbcTemplate);
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                entity("primary", "主要实体", 0.8f, 3),
                entity("secondary", "重复实体", 0.7f, 1)));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("secondary", 0.95f)), List.of());
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThrows(IllegalStateException.class, deduplicator::dedup);
    }

    @Test
    @DisplayName("重复候选应完成合并并统计")
    void 重复候选应完成合并并统计() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var deduplicator = deduplicator(semanticMemory, vectorSearcher, jdbcTemplate);
        var primary = entity("primary", "主要实体", 0.8f, 3);
        var secondary = entity("secondary", "重复实体", 0.7f, 1);
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(primary, secondary));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("secondary", 0.95f)),
                        List.of(new VectorSearchResult("primary", 0.95f)));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1, 0);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var stats = deduplicator.dedup();

        assertEquals(2, stats.entitiesScanned());
        assertEquals(1, stats.candidatesFound());
        assertEquals(1, stats.mergedCount());
        verify(semanticMemory).archive(eq(secondary), any());
    }

    private static EntityDeduplicator deduplicator(SemanticMemory semanticMemory,
                                                   VectorSearcher vectorSearcher,
                                                   JdbcTemplate jdbcTemplate) {
        return new EntityDeduplicator(
                semanticMemory,
                vectorSearcher,
                jdbcTemplate,
                new AgentLearningProperties(),
                mock(PlatformTransactionManager.class));
    }

    private static TemporalEntity entity(String id, String name, float importance, int accessCount) {
        var now = Instant.parse("2026-06-29T00:00:00Z");
        return new TemporalEntity(
                id,
                EntityType.TOPIC,
                name,
                "描述 " + name,
                Map.of("name", name),
                1,
                true,
                now,
                null,
                "session-1",
                0.9f,
                importance,
                accessCount,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                MemoryEvidenceKind.USER_EXPLICIT,
                MemoryTrustLevel.VERIFIED,
                0.9f,
                1,
                now);
    }
}
