package com.lifepilot.memory.retrieval;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.rerank.router.RerankRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetriever 作用域过滤测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
class HybridRetriever作用域过滤测试 {

    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private MemoryRetrievalProperties properties;
    private MemoryProvenanceRepository provenanceRepository;

    @BeforeEach
    void setUp() {
        vectorSearcher = mock(VectorSearcher.class);
        ftsSearcher = mock(FtsSearcher.class);
        graphTraverser = mock(GraphTraverser.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        provenanceRepository = mock(MemoryProvenanceRepository.class);
        properties = new MemoryRetrievalProperties();
        properties.setMinVectorSimilarity(0.0f);
        properties.setMinFusedScore(0.0f);
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(1);
        when(provenanceRepository.findStaleEntityIds(anyCollection())).thenReturn(Set.of());
    }

    @Test
    void userMemory过滤应排除领域记忆结果() {
        var userEntity = buildEntity("user-memory", EntityType.TOPIC, "个人偏好", "个人事实");
        var domainEntity = buildEntity("domain-memory", EntityType.TOPIC, "小说角色", "领域设定");

        // HybridRetriever 实际走 4 参重载（带 eligibleIds pre-filter 参数）
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(),
                org.mockito.ArgumentMatchers.<java.util.Set<String>>any())).thenReturn(List.of(
                new VectorSearchResult(userEntity.id(), 0.91f),
                new VectorSearchResult(domainEntity.id(), 0.89f)
        ));
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(List.of(
                new RankedItem(domainEntity.id(), domainEntity.type().name(), domainEntity.name(),
                        domainEntity.description(), 0.8f, Instant.now(), 0.5f, null, Instant.now())
        ));
        when(graphTraverser.traverse(anyString(), anyInt(), eq(MemoryReadFilter.userMemory()))).thenReturn(List.of(
                new RankedItem(domainEntity.id(), domainEntity.type().name(), domainEntity.name(),
                        domainEntity.description(), 1.0f, Instant.now(), 0.5f, null, Instant.now())
        ));

        var filteredEntities = new HashMap<String, TemporalEntity>();
        filteredEntities.put(userEntity.id(), userEntity);

        when(semanticMemory.findByIds(anyCollection(), eq(MemoryReadFilter.userMemory())))
                .thenReturn(filteredEntities);
        when(semanticMemory.findEligibleEntityIds(eq(MemoryReadFilter.userMemory())))
                .thenReturn(Set.of(userEntity.id()));

        var retriever = new HybridRetriever(
                vectorSearcher,
                ftsSearcher,
                graphTraverser,
                semanticMemory,
                null,
                properties,
                jdbcTemplate,
                mock(RerankRouter.class),
                provenanceRepository);

        var results = retriever.retrieve("帮我回忆一下", 10, RetrievalWeights.DEFAULT, MemoryReadFilter.userMemory());

        assertThat(results)
                .extracting(RetrievalResult::entityId)
                .containsExactly(userEntity.id());
    }

    private TemporalEntity buildEntity(String id, EntityType type, String name, String description) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("scope", MemoryScope.USER_FACT.name());
        return new TemporalEntity(
                id,
                type,
                name,
                description,
                properties,
                1,
                true,
                Instant.now(),
                null,
                null,
                0.9f,
                0.5f,
                0,
                Instant.now(),
                Instant.now(),
                Instant.now()
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
    }
}
