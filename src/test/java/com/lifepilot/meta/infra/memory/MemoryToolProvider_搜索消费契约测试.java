package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryToolProvider 搜索消费契约测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("MemoryToolProvider 搜索消费契约")
class MemoryToolProvider_搜索消费契约测试 {

    private HybridRetriever hybridRetriever;
    private SemanticMemory semanticMemory;
    private DynamicToolRegistry registry;

    @BeforeEach
    void 初始化() {
        hybridRetriever = mock(HybridRetriever.class);
        semanticMemory = mock(SemanticMemory.class);
        registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));

        var provider = new MemoryToolProvider(
                hybridRetriever,
                semanticMemory,
                mock(EpisodicMemory.class),
                mock(DocumentRetriever.class),
                mock(SessionKnowledgeBaseRepository.class),
                mock(SessionKnowledgeScopeResolver.class),
                new MemoryProperties());
        provider.registerTools(registry);
    }

    @Test
    @SuppressWarnings("unchecked")
    void search只返回可消费记忆并暴露质量和分数明细() {
        var verified = 实体("verified-search", EntityType.TOPIC, "可信搜索结果")
                .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.92f, 1, Instant.now());
        var unverified = 实体("unverified-search", EntityType.TOPIC, "未知搜索结果");
        var verifiedResult = 检索结果(verified.id(), verified.type(), 0.86f);
        var unverifiedResult = 检索结果(unverified.id(), unverified.type(), 0.91f);

        when(hybridRetriever.retrieve(anyString(), anyInt(), any(RetrievalWeights.class), any(MemoryReadFilter.class)))
                .thenReturn(List.of(unverifiedResult, verifiedResult));
        when(semanticMemory.findByIds(any(Collection.class), any(MemoryReadFilter.class)))
                .thenReturn(Map.of(verified.id(), verified, unverified.id(), unverified));

        var tool = registry.resolve("memory").orElseThrow();
        var result = tool.execute(new ToolInput(tool.id(),
                Map.of("action", "search", "query", "搜索", "top_k", 2),
                tool.inputSchema(), null, Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("rawCount")).isEqualTo(2);
        assertThat(result.<Integer>getData("qualityFilteredCount")).isEqualTo(1);
        assertThat(result.<Integer>getData("truncatedCount")).isEqualTo(0);
        assertThat(result.<Integer>getData("filteredOutCount")).isEqualTo(1);
        List<Map<String, Object>> items = result.getData("results");
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.getFirst();
        assertThat(item.get("entityId")).isEqualTo(verified.id());
        assertThat(item).containsKeys("quality", "lifecycle", "scoreBreakdown", "importanceScore");

        Map<String, Object> quality = (Map<String, Object>) item.get("quality");
        assertThat(quality.get("trustLevel")).isEqualTo("EXPLICIT");
        assertThat(quality.get("evidenceKind")).isEqualTo("USER_CONFIRMED");
        Map<String, Object> scoreBreakdown = (Map<String, Object>) item.get("scoreBreakdown");
        assertThat(scoreBreakdown).containsKeys("trustBoost", "lifecycleAdjustment");

        ArgumentCaptor<List<RetrievalResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(hybridRetriever).updateAccessCounts(captor.capture());
        assertThat(captor.getValue()).containsExactly(verifiedResult);
    }

    private TemporalEntity 实体(String id, EntityType type, String name) {
        var now = Instant.parse("2026-05-05T00:00:00Z");
        return new TemporalEntity(
                id,
                type,
                name,
                "测试描述",
                Map.of(),
                1,
                true,
                now,
                null,
                "session-1",
                0.8f,
                0.7f,
                0,
                null,
                now,
                now);
    }

    private RetrievalResult 检索结果(String id, EntityType type, float score) {
        return new RetrievalResult(
                id,
                type.name(),
                "name-" + id,
                "desc",
                score,
                new RetrievalResult.ScoreBreakdown(score, score, 0.1f, 0.1f, 0.0f, 0.0f, 0.0f, 0.2f, 0.0f, 0.0f),
                "vector+fts",
                null,
                0.7f,
                null,
                false,
                false,
                false);
    }
}
