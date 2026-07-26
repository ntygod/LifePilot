package com.lifepilot.memory.retrieval;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalAutoConfiguration;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.rerank.router.RerankRouter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MemoryRetrievalAutoConfiguration 单元测试。
 *
 * @author zsg
 * @since 2026-07-06
 */
class MemoryRetrievalAutoConfiguration_单元测试 {

    @Test
    void 默认关闭L4意图匹配时不向HybridRetriever注入Matcher() {
        var properties = new MemoryRetrievalProperties();
        var intentMatcher = mock(IntentMatcher.class);

        var retriever = buildRetriever(properties, intentMatcher);

        assertThat(ReflectionTestUtils.getField(retriever, "intentMatcher")).isNull();
    }

    @Test
    void 显式开启L4意图匹配时才向HybridRetriever注入Matcher() {
        var properties = new MemoryRetrievalProperties();
        properties.setIntentMatchEnabled(true);
        var intentMatcher = mock(IntentMatcher.class);

        var retriever = buildRetriever(properties, intentMatcher);

        assertThat(ReflectionTestUtils.getField(retriever, "intentMatcher")).isSameAs(intentMatcher);
    }

    private HybridRetriever buildRetriever(MemoryRetrievalProperties properties, IntentMatcher intentMatcher) {
        var configuration = new MemoryRetrievalAutoConfiguration();
        return configuration.hybridRetriever(
                mock(VectorSearcher.class),
                mock(FtsSearcher.class),
                mock(GraphTraverser.class),
                mock(SemanticMemory.class),
                mock(EpisodicMemory.class),
                intentMatcher,
                mock(RerankRouter.class),
                mock(JdbcTemplate.class),
                properties,
                mock(MemoryProvenanceRepository.class)
        );
    }
}
