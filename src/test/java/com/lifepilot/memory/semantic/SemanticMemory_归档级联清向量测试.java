package com.lifepilot.memory.semantic;

import com.lifepilot.memory.retrieval.VectorSearcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    private SemanticMemory semanticMemory;

    @BeforeEach
    void 初始化() {
        semanticMemory = new SemanticMemory(
                jdbcTemplate,
                conflictDetector,
                new VersionMerger(),
                vectorSearcher);
    }

    @Test
    void 归档实体应同时删除对应向量索引() {
        var entity = 构造实体("entity-diary", EntityType.GOAL, "写日记");

        semanticMemory.archive(entity);

        // 核心断言：vectorSearcher.deleteEntityVector 被调用一次，参数是实体 ID
        verify(vectorSearcher).deleteEntityVector(eq("entity-diary"));
    }

    @Test
    void 清向量失败不应影响归档事务_仅告警() {
        var entity = 构造实体("entity-x", EntityType.GOAL, "X");
        doThrow(new RuntimeException("向量库不可用"))
                .when(vectorSearcher).deleteEntityVector(anyString());

        // 不应抛出异常 — 跨库操作失败时归档主事务仍应成功
        semanticMemory.archive(entity);

        verify(vectorSearcher).deleteEntityVector(eq("entity-x"));
    }

    private TemporalEntity 构造实体(String id, EntityType type, String name) {
        var now = Instant.now();
        return new TemporalEntity(
                id, type, name, "测试描述", Map.of(),
                1, true, now, null, "session-1",
                0.8f, 0.5f, 0, null, now, now);
    }
}
