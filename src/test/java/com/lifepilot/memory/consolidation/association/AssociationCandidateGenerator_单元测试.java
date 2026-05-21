package com.lifepilot.memory.consolidation.association;

import com.lifepilot.agent.learning.consolidation.association.AssociationCandidateGenerator;
import com.lifepilot.agent.learning.consolidation.association.AssociationType;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AssociationCandidateGenerator 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
class AssociationCandidateGenerator_单元测试 {

    @Test
    void 开关关闭时直接返回空() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(false);

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);
        assertThat(gen.generate()).isEmpty();
    }

    @Test
    void 缺依赖时返回空() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(true);

        var gen = new AssociationCandidateGenerator(sem, null, null, props);
        assertThat(gen.generate()).isEmpty();
    }

    @Test
    void seed按importance降序选top_K() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        props.getRem().setSeedLimit(2);
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(
                        entity("g1", 0.5f, "目标 1"),
                        entity("g2", 0.9f, "目标 2"),
                        entity("g3", 0.7f, "目标 3")
                ));

        var gen = new AssociationCandidateGenerator(sem, null, null, props);
        var seeds = gen.selectSeeds();
        assertThat(seeds).hasSize(2);
        assertThat(seeds.get(0).id()).isEqualTo("g2");
        assertThat(seeds.get(1).id()).isEqualTo("g3");
    }

    @Test
    void 描述为空的seed被过滤() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(
                        entity("g1", 0.9f, ""),
                        entity("g2", 0.5f, "有描述")
                ));

        var gen = new AssociationCandidateGenerator(sem, null, null, props);
        var seeds = gen.selectSeeds();
        assertThat(seeds).hasSize(1);
        assertThat(seeds.getFirst().id()).isEqualTo("g2");
    }

    @Test
    void LLM返回有效JSON时能解析出候选() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(true);
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));
        props.getRem().setSeedLimit(1);

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(entity("g1", 0.8f, "学习 Rust")));
        when(retriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(retrievalResult("n1", "Rust 书籍")));

        String llmJson = "一些前缀文字 [{\"sourceId\":\"n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":0.85,\"evidence\":\"书籍是学习资源\"}] 尾部";
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenReturn(new LlmResponse(llmJson, null, null, List.of(), Map.of(), 0, 0, null, 0, "mock", "mock", 0, false));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);
        var result = gen.generate();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().sourceEntityId()).isEqualTo("n1");
        assertThat(result.getFirst().targetEntityId()).isEqualTo("g1");
        assertThat(result.getFirst().relationType()).isEqualTo(AssociationType.SUPPORTS);
        assertThat(result.getFirst().confidence()).isEqualTo(0.85f);
    }

    @Test
    void LLM返回非JSON时返回空() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(true);
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(entity("g1", 0.8f, "学习 Rust")));
        when(retriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(retrievalResult("n1", "Rust 书籍")));
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenReturn(new LlmResponse("没有 JSON 数组", null, null, List.of(), Map.of(), 0, 0, null, 0, "mock", "mock", 0, false));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);
        assertThat(gen.generate()).isEmpty();
    }

    @Test
    void LLM异常时返回空_不抛() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(true);
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(entity("g1", 0.8f, "学习 Rust")));
        when(retriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(retrievalResult("n1", "Rust 书籍")));
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenThrow(new RuntimeException("模拟 LLM 不可用"));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);
        assertThat(gen.generate()).isEmpty();
    }

    @Test
    void 未知关系类型降级为RELATED_TO() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(true);
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(entity("g1", 0.8f, "目标")));
        when(retriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(retrievalResult("n1", "邻居")));
        String llmJson = "[{\"sourceId\":\"n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"UNKNOWN_TYPE\",\"confidence\":0.8}]";
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenReturn(new LlmResponse(llmJson, null, null, List.of(), Map.of(), 0, 0, null, 0, "mock", "mock", 0, false));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);
        var result = gen.generate();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().relationType()).isEqualTo(AssociationType.RELATED_TO);
    }

    private TemporalEntity entity(String id, float importance, String desc) {
        Instant now = Instant.now();
        return new TemporalEntity(
                id, EntityType.GOAL, "name-" + id, desc,
                Map.of(), 1, true, now, null, null,
                0.8f, importance, 0, null, now, now);
    }

    private RetrievalResult retrievalResult(String id, String name) {
        return new RetrievalResult(id, "GOAL", name, "描述",
                0.7f,
                new RetrievalResult.ScoreBreakdown(0.7f, 0.3f, 0.5f, 0.15f, 0.0f, 0.0f, 0f, 0f, 0f, 0f),
                "vector", null, 0.6f, null, false, false, false);
    }
}
