package com.lifepilot.agent.learning.consolidation.association;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void 缺retriever时构造失败() {
        var sem = mock(SemanticMemory.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();

        assertThatThrownBy(() -> new AssociationCandidateGenerator(sem, null, router, props))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hybridRetriever 不能为空");
    }

    @Test
    void 缺router时构造失败() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var props = new AgentLearningProperties();

        assertThatThrownBy(() -> new AssociationCandidateGenerator(sem, retriever, null, props))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generationRouter 不能为空");
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

        var gen = generator(sem, props);
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

        var gen = generator(sem, props);
        var seeds = gen.selectSeeds();
        assertThat(seeds).hasSize(1);
        assertThat(seeds.getFirst().id()).isEqualTo("g2");
    }

    @Test
    void seedTypes包含未知实体类型时应失败() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        props.getRem().setSeedTypes(java.util.Set.of("GOAL", "UNKNOWN_TYPE"));

        var gen = generator(sem, props);

        assertThatThrownBy(gen::selectSeeds)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 REM seedTypes 实体类型: UNKNOWN_TYPE");
    }

    @Test
    void seedTypes包含首尾空白时应失败() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        props.getRem().setSeedTypes(java.util.Set.of(" GOAL"));

        var gen = generator(sem, props);

        assertThatThrownBy(gen::selectSeeds)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REM seedTypes不能包含首尾空白");
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

        String llmJson = "[{\"sourceId\":\"n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":0.85,\"evidence\":\"书籍是学习资源\"}]";
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
    void 邻居检索返回null时应失败() {
        var sem = mock(SemanticMemory.class);
        var retriever = mock(HybridRetriever.class);
        var router = mock(GenerationRouter.class);
        var props = new AgentLearningProperties();
        props.getRem().setEnabled(true);
        props.getRem().setSeedTypes(java.util.Set.of("GOAL"));

        when(sem.findCurrentByType(EntityType.GOAL))
                .thenReturn(List.of(entity("g1", 0.8f, "学习 Rust")));
        when(retriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(null);

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);

        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 邻居检索结果不能为空");
    }

    @Test
    void LLM返回sourceId不在邻居集中时应失败() {
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
        String llmJson = "[{\"sourceId\":\"n2\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":0.85,\"evidence\":\"书籍是学习资源\"}]";
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenReturn(new LlmResponse(llmJson, null, null, List.of(), Map.of(),
                        0, 0, null, 0, "mock", "mock", 0, false));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);

        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 联想候选 sourceId 不在邻居集中");
    }

    @Test
    void LLM返回targetId不是seed时应失败() {
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
        String llmJson = "[{\"sourceId\":\"n1\",\"targetId\":\"g2\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":0.85,\"evidence\":\"书籍是学习资源\"}]";
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenReturn(new LlmResponse(llmJson, null, null, List.of(), Map.of(),
                        0, 0, null, 0, "mock", "mock", 0, false));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);

        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 联想候选 targetId 必须等于 seedId");
    }

    @Test
    void LLM返回非JSON时按契约失败() {
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
        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 联想数组解析失败");
    }

    @Test
    void LLM返回带前后缀的JSON时按契约失败() {
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
        String llmJson = "前缀 [{\"sourceId\":\"n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":0.85}] 后缀";
        when(router.call(anyString(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any(), anyBoolean()))
                .thenReturn(new LlmResponse(llmJson, null, null, List.of(), Map.of(),
                        0, 0, null, 0, "mock", "mock", 0, false));

        var gen = new AssociationCandidateGenerator(sem, retriever, router, props);
        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REM 联想数组解析失败");
    }

    @Test
    void LLM异常时直接暴露() {
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
        assertThatThrownBy(gen::generate)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模拟 LLM 不可用");
    }

    @Test
    void 未知关系类型应按契约失败() {
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
        assertThatThrownBy(gen::generate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知关系类型: UNKNOWN_TYPE");
    }

    @Test
    void confidence缺失时应按契约失败() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        var gen = generator(sem, props);
        String llmJson = "[{\"sourceId\":\"n1\",\"targetId\":\"g1\",\"relationType\":\"SUPPORTS\"}]";

        assertThatThrownBy(() -> gen.parseResponse(llmJson, "g1", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 不能为空");
    }

    @Test
    void 候选ID含首尾空白时应按契约失败() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        var gen = generator(sem, props);
        String llmJson = "[{\"sourceId\":\" n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":0.8}]";

        assertThatThrownBy(() -> gen.parseResponse(llmJson, "g1", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REM 联想候选 sourceId不能包含首尾空白");
    }

    @Test
    void confidence非数值时应按契约失败() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        var gen = generator(sem, props);
        String llmJson = "[{\"sourceId\":\"n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":\"high\"}]";

        assertThatThrownBy(() -> gen.parseResponse(llmJson, "g1", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 必须是数值");
    }

    @Test
    void confidence越界时应按契约失败() {
        var sem = mock(SemanticMemory.class);
        var props = new AgentLearningProperties();
        var gen = generator(sem, props);
        String llmJson = "[{\"sourceId\":\"n1\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":1.2},"
                + "{\"sourceId\":\"n2\",\"targetId\":\"g1\","
                + "\"relationType\":\"SUPPORTS\",\"confidence\":-0.1}]";

        assertThatThrownBy(() -> gen.parseResponse(llmJson, "g1", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence 必须在 [0,1] 范围内");
    }

    private AssociationCandidateGenerator generator(SemanticMemory sem, AgentLearningProperties props) {
        return new AssociationCandidateGenerator(
                sem, mock(HybridRetriever.class), mock(GenerationRouter.class), props);
    }

    private TemporalEntity entity(String id, float importance, String desc) {
        Instant now = Instant.now();
        return new TemporalEntity(
                id, EntityType.GOAL, "name-" + id, desc,
                Map.of(), 1, true, now, null, null,
                0.8f, importance, 0, null, now, now,
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
                        now);
    }

    private RetrievalResult retrievalResult(String id, String name) {
        return new RetrievalResult(id, "GOAL", name, "描述",
                0.7f,
                new RetrievalResult.ScoreBreakdown(0.7f, 0.3f, 0.5f, 0.15f, 0.0f, 0.0f, 0f, 0f, 0f, 0f),
                "vector", null, 0.6f, null, false, false, false);
    }
}
