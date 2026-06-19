package com.lifepilot.memory.semantic;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.*;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * SemanticMemory.archive() 级联清向量行为验证。
 *
 * <p>背景：如果 archive 仅更新 memory_entities.status='ARCHIVED' 但保留 entity_embeddings 中的向量，
 * VectorSearcher 会继续召回已归档实体（其 SQL 不 JOIN 状态），导致"取消后仍被提醒"现象。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMemory 归档级联清向量测试")
class SemanticMemory_归档级联清向量测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ConflictDetector conflictDetector;
    @Mock
    private VectorSearcher vectorSearcher;
    @Mock
    private MemoryProjectionService projectionService;

    private SemanticMemory semanticMemory;

    @BeforeEach
    void 初始化() {
        semanticMemory = new SemanticMemory(
                jdbcTemplate,
                conflictDetector,
                new VersionMerger(),
                vectorSearcher);
        semanticMemory.setProjectionService(projectionService);
    }

    @Test
    void 归档实体应提交向量删除投影任务() {
        var entity = 构造实体("entity-diary", EntityType.GOAL, "写日记");

        semanticMemory.archive(entity, ChangeSource.UI_EDIT);

        verify(projectionService).enqueueVectorDeleteAfterCommit(eq("entity-diary"));
    }

    @Test
    void 未装配投影服务时应拒绝归档以避免绕过outbox() {
        var memoryWithoutProjection = new SemanticMemory(
                jdbcTemplate,
                conflictDetector,
                new VersionMerger(),
                vectorSearcher);
        var entity = 构造实体("entity-x", EntityType.GOAL, "X");

        assertThatThrownBy(() -> memoryWithoutProjection.archive(entity, ChangeSource.UI_EDIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MemoryProjectionService 未装配");
    }

    private TemporalEntity 构造实体(String id, EntityType type, String name) {
        var now = Instant.now();
        return new TemporalEntity(
                id, type, name, "测试描述", Map.of(),
                1, true, now, null, "session-1",
                0.8f, 0.5f, 0, null, now, now);
    }
}
