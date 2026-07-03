package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        var kb = knowledgeSource();
        var planner = new QueryPlanner(hybrid, exp, kb);

        var result = planner.plan("q", RetrievalIntent.FACT);
        assertThat(result).containsExactly(hybrid, kb);
    }

    @Test
    void EXPERIENCE_intent_选择experience_和_hybrid() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var planner = new QueryPlanner(hybrid, exp, knowledgeSource());

        var result = planner.plan("q", RetrievalIntent.EXPERIENCE);
        assertThat(result).containsExactly(exp, hybrid);
    }

    @Test
    void GENERAL_intent_三路全走() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var kb = knowledgeSource();
        var planner = new QueryPlanner(hybrid, exp, kb);

        var result = planner.plan("q", RetrievalIntent.GENERAL);
        assertThat(result).containsExactly(hybrid, exp, kb);
    }

    @Test
    void null_intent_当作GENERAL() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var kb = knowledgeSource();
        var planner = new QueryPlanner(hybrid, exp, kb);

        var result = planner.plan("q", null);
        assertThat(result).containsExactly(hybrid, exp, kb);
    }

    @Test
    void source缺失时构造失败() {
        var hybrid = new HybridRetrievalSource(mock(HybridRetriever.class));
        var exp = new ExperienceRetrievalSource(mock(SemanticMemory.class));
        var kb = knowledgeSource();

        assertThatThrownBy(() -> new QueryPlanner(null, exp, kb))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hybridSource 不能为空");
        assertThatThrownBy(() -> new QueryPlanner(hybrid, null, kb))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("experienceSource 不能为空");
        assertThatThrownBy(() -> new QueryPlanner(hybrid, exp, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("knowledgeBaseSource 不能为空");
    }

    private KnowledgeBaseSource knowledgeSource() {
        return new KnowledgeBaseSource(
                mock(DocumentRetriever.class),
                mock(KnowledgeBaseRepository.class));
    }
}
