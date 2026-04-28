package com.lifepilot.rerank.router;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.DocumentSourceType;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.llm.thinking.ThinkingMode;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.model.RerankExecutionMode;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import com.lifepilot.rerank.client.RerankClientFactory;
import com.lifepilot.rerank.client.RerankServiceClient;
import com.lifepilot.rerank.strategy.LlmListwiseRerankStrategy;
import com.lifepilot.rerank.strategy.LlmPointwiseRerankStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RerankRouter 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class RerankRouterTest {

    @Test
    void 原生精排优先使用显式模型名() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(RerankSettingsRepository.class);
        var clientFactory = mock(RerankClientFactory.class);
        var pointwiseStrategy = mock(LlmPointwiseRerankStrategy.class);
        var listwiseStrategy = mock(LlmListwiseRerankStrategy.class);
        var client = mock(RerankServiceClient.class);

        var explicitService = rerankService("rerank-explicit", "bge-reranker");
        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        true,
                        RerankExecutionMode.NATIVE,
                        "rerank-default",
                        null,
                        3,
                        true,
                        8
                )));
        when(registry.findEnabledByModelName(ModelServiceKind.RERANK, "bge-reranker"))
                .thenReturn(List.of(explicitService));
        when(clientFactory.getOrCreate(explicitService)).thenReturn(client);
        when(client.rerank(eq("查询"), anyList(), eq(3), eq(null)))
                .thenReturn(List.of(
                        new RerankServiceClient.RerankScore(1, 0.95),
                        new RerankServiceClient.RerankScore(0, 0.80)
                ));

        var router = new RerankRouter(registry, settingsRepository, clientFactory, pointwiseStrategy, listwiseStrategy);
        var results = router.rerankDocuments("查询", List.of(document("c1", 0.2), document("c2", 0.1)), 10, "bge-reranker");

        assertEquals(2, results.size());
        assertEquals("c2", results.getFirst().chunkId());
        assertEquals(0.95, results.getFirst().score());
        verify(client).rerank(eq("查询"), anyList(), eq(3), eq(null));
    }

    @Test
    void LlmPointwise模式委托给点式策略() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(RerankSettingsRepository.class);
        var clientFactory = mock(RerankClientFactory.class);
        var pointwiseStrategy = mock(LlmPointwiseRerankStrategy.class);
        var listwiseStrategy = mock(LlmListwiseRerankStrategy.class);

        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        true,
                        RerankExecutionMode.LLM_POINTWISE,
                        null,
                        "generation-rerank",
                        5,
                        true,
                        6
                )));
        when(pointwiseStrategy.rerankCandidates(
                eq("记忆查询"),
                anyList(),
                eq(6),
                eq(null),
                eq("generation-rerank")))
                .thenReturn(List.of(new RerankCandidate("m2", "内容2", 0.9)));

        var router = new RerankRouter(registry, settingsRepository, clientFactory, pointwiseStrategy, listwiseStrategy);
        var results = router.rerankMemoryCandidates(
                "记忆查询",
                List.of(new RerankCandidate("m1", "内容1", 0.2), new RerankCandidate("m2", "内容2", 0.1))
        );

        assertEquals(1, results.size());
        assertEquals("m2", results.getFirst().id());
        verify(pointwiseStrategy).rerankCandidates(eq("记忆查询"), anyList(), eq(6), eq(null), eq("generation-rerank"));
    }

    @Test
    void 记忆精排启用状态受总开关和记忆开关共同控制() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(RerankSettingsRepository.class);
        var clientFactory = mock(RerankClientFactory.class);
        var pointwiseStrategy = mock(LlmPointwiseRerankStrategy.class);
        var listwiseStrategy = mock(LlmListwiseRerankStrategy.class);

        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        true,
                        RerankExecutionMode.LLM_LISTWISE,
                        null,
                        "generation-rerank",
                        5,
                        true,
                        10
                )));
        var router = new RerankRouter(registry, settingsRepository, clientFactory, pointwiseStrategy, listwiseStrategy);
        assertTrue(router.isKnowledgeRerankEnabled());
        assertTrue(router.isMemoryRerankEnabled());
        assertEquals(4, router.resolveKnowledgeTopK(4));

        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        true,
                        RerankExecutionMode.LLM_LISTWISE,
                        null,
                        "generation-rerank",
                        5,
                        false,
                        10
                )));
        assertFalse(router.isMemoryRerankEnabled());
    }

    private ModelServiceEntity rerankService(String id, String modelName) {
        return new ModelServiceEntity(
                id,
                ModelServiceKind.RERANK,
                "tei-local",
                "http://localhost:8082",
                null,
                modelName,
                30,
                0,
                true,
                false,
                ThinkingMode.AUTO,
                List.of(),
                Set.of(),
                Map.of(),
                id,
                id + " description"
        );
    }

    private DocumentSearchResult document(String chunkId, double score) {
        return new DocumentSearchResult(
                chunkId,
                "doc-" + chunkId,
                "kb-1",
                "内容-" + chunkId,
                Optional.empty(),
                List.of("标题"),
                score,
                "fused",
                Map.of(),
                Optional.of(new ScoreBreakdown(0.1, 0.2, 0.0, score, Optional.empty())),
                Optional.empty(),
                DocumentSourceType.FILE,
                Optional.empty(),
                Optional.empty()
        );
    }
}
