package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QueryPlanner 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class QueryPlanner_单元测试 {

    @Test
    void FACT_intent_选择hybrid_和_knowledgeBase() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var kb = new KnowledgeBaseSource();  // isAvailable=false
        var planner = new QueryPlanner(hybrid, exp, kb);

        var result = planner.plan("q", RetrievalIntent.FACT);
        // kb 不可用 → 只剩 hybrid
        assertThat(result).containsExactly(hybrid);
    }

    @Test
    void EXPERIENCE_intent_选择experience_和_hybrid() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var planner = new QueryPlanner(hybrid, exp, null);

        var result = planner.plan("q", RetrievalIntent.EXPERIENCE);
        assertThat(result).containsExactly(exp, hybrid);
    }

    @Test
    void GENERAL_intent_三路全走() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var kb = new KnowledgeBaseSource();
        var planner = new QueryPlanner(hybrid, exp, kb);

        var result = planner.plan("q", RetrievalIntent.GENERAL);
        // kb isAvailable=false 仍被过滤
        assertThat(result).containsExactly(hybrid, exp);
    }

    @Test
    void null_intent_当作GENERAL() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var planner = new QueryPlanner(hybrid, null, null);

        var result = planner.plan("q", null);
        assertThat(result).containsExactly(hybrid);
    }

    @Test
    void 全不可用返回空() {
        var planner = new QueryPlanner(null, null, null);
        assertThat(planner.plan("q", RetrievalIntent.FACT)).isEmpty();
        assertThat(planner.plan("q", RetrievalIntent.EXPERIENCE)).isEmpty();
        assertThat(planner.plan("q", RetrievalIntent.GENERAL)).isEmpty();
    }
}
