package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetriever 归档实体不应召回测试。
 *
 * <p>回归场景：向量表残留归档实体的 embedding 时，findByIds 因 is_current=1 过滤而找不到该实体。
 * 旧实现会以 "UNKNOWN" 类型把 entityId 回退进结果，造成下游上下文注入脏数据。
 * 修复后应直接丢弃这种命中。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@DisplayName("HybridRetriever 归档实体不应召回测试")
class HybridRetriever_归档实体不应召回测试 {

    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private MemoryProperties properties;

    @BeforeEach
    void 初始化() {
        vectorSearcher = mock(VectorSearcher.class);
        ftsSearcher = mock(FtsSearcher.class);
        graphTraverser = mock(GraphTraverser.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        properties = new MemoryProperties();
        properties.getRetrieval().setMinVectorSimilarity(0.0f);
        properties.getRetrieval().setMinFusedScore(0.0f);
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(1);
    }

    @Test
    void 向量召回已归档实体_findByIds返回空_不应以UNKNOWN回退进结果() {
        // 模拟：向量命中一个 entityId，但 findByIds 查不到对应实体（已归档、is_current=0）
        String archivedId = "archived-entity-id";
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult(archivedId, 0.95f)));
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(List.of());

        // findByIds(无 filter) 返回空 map — 归档实体被 is_current=1 过滤掉
        when(semanticMemory.findByIds(anyCollection())).thenReturn(Map.of());

        var retriever = new HybridRetriever(
                vectorSearcher,
                ftsSearcher,
                graphTraverser,
                semanticMemory,
                null,
                properties,
                jdbcTemplate,
                null, null);

        var results = retriever.retrieve("任意查询", 10, RetrievalWeights.DEFAULT);

        // 关键断言：归档实体不应出现在结果中（旧实现会以 UNKNOWN 类型回退）
        assertThat(results)
                .as("归档实体的 entityId 不应以 UNKNOWN 类型回退进检索结果")
                .extracting(RetrievalResult::entityId)
                .doesNotContain(archivedId);
        assertThat(results)
                .as("三路检索其他均为空，归档实体又被剔除，结果集应为空")
                .isEmpty();
    }
}
