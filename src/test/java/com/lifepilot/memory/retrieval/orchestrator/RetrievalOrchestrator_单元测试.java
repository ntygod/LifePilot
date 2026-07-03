package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RetrievalOrchestrator 单元测试（含 Source/Planner 联动）。
 *
 * @author zsg
 * @since 2026-05-09
 */
class RetrievalOrchestrator_单元测试 {

    @Test
    void 空查询返回空bundle() {
        var props = new MemoryRetrievalProperties();
        var planner = planner(mock(HybridRetriever.class), mock(SemanticMemory.class));
        var orchestrator = new RetrievalOrchestrator(planner, props);

        assertThat(orchestrator.retrieve("").items()).isEmpty();
        assertThat(orchestrator.retrieve(null, RetrievalIntent.GENERAL, 10).items()).isEmpty();
    }

    @Test
    void Hybrid返回item按score降序() {
        var hybridRetriever = mock(HybridRetriever.class);
        when(hybridRetriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        retrievalResult("e1", "A", 0.5f),
                        retrievalResult("e2", "B", 0.9f),
                        retrievalResult("e3", "C", 0.7f)
                ));
        var hybridSource = new HybridRetrievalSource(hybridRetriever);
        var planner = new QueryPlanner(
                hybridSource,
                new ExperienceRetrievalSource(mock(SemanticMemory.class)),
                emptyKnowledgeSource());
        var props = new MemoryRetrievalProperties();
        var orchestrator = new RetrievalOrchestrator(planner, props);

        var bundle = orchestrator.retrieve("test", RetrievalIntent.FACT, 3);

        assertThat(bundle.items()).hasSize(3);
        assertThat(bundle.items().get(0).entityId()).isEqualTo("e2");
        assertThat(bundle.items().get(1).entityId()).isEqualTo("e3");
        assertThat(bundle.items().get(2).entityId()).isEqualTo("e1");
        assertThat(bundle.sources()).contains("hybrid");
    }

    @Test
    void Experience源按文本匹配返回() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(
                        experienceEntity("exp-1", "Rust 学习经验", "在学习 Rust 的过程中..."),
                        experienceEntity("exp-2", "Java 经验", "Java 相关")
                ));
        var hybrid = mock(HybridRetriever.class);
        when(hybrid.retrieve(anyString(), anyInt(), any())).thenReturn(List.of());
        var expSource = new ExperienceRetrievalSource(semanticMemory);
        var planner = new QueryPlanner(
                new HybridRetrievalSource(hybrid),
                expSource,
                emptyKnowledgeSource());
        var orchestrator = new RetrievalOrchestrator(planner, new MemoryRetrievalProperties());

        var bundle = orchestrator.retrieve("Rust", RetrievalIntent.EXPERIENCE, 10);

        assertThat(bundle.items()).hasSize(1);
        assertThat(bundle.items().getFirst().entityId()).isEqualTo("exp-1");
        assertThat(bundle.sources()).contains("experience");
    }

    @Test
    void Experience源返回null实体应直接失败() {
        var semanticMemory = mock(SemanticMemory.class);
        var entities = new ArrayList<TemporalEntity>();
        entities.add(null);
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(entities);
        var expSource = new ExperienceRetrievalSource(semanticMemory);

        assertThatThrownBy(() -> expSource.retrieve("Rust", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("经验检索源返回 null 实体");
    }

    @Test
    void Experience源实体缺描述应直接失败() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(experienceEntity("exp-bad", "Rust 学习经验", null)));
        var expSource = new ExperienceRetrievalSource(semanticMemory);

        assertThatThrownBy(() -> expSource.retrieve("Rust", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("经验检索实体描述不能为空");
    }

    @Test
    void 无匹配结果时返回空bundle() {
        var hybrid = mock(HybridRetriever.class);
        when(hybrid.retrieve(anyString(), anyInt(), any())).thenReturn(List.of());
        var sem = mock(SemanticMemory.class);
        when(sem.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of());
        var planner = planner(hybrid, sem);
        var orchestrator = new RetrievalOrchestrator(planner, new MemoryRetrievalProperties());

        var bundle = orchestrator.retrieve("test", RetrievalIntent.GENERAL, 10);

        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.sources()).isEmpty();
    }

    @Test
    void 单源topK配置非法时抛异常() {
        var props = new MemoryRetrievalProperties();
        props.getOrchestrator().setPerSourceTopK(0);
        var planner = planner(mock(HybridRetriever.class), mock(SemanticMemory.class));
        var orchestrator = new RetrievalOrchestrator(planner, props);

        assertThatThrownBy(() -> orchestrator.retrieve("test", RetrievalIntent.GENERAL, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("检索编排单源 topK必须大于 0");
    }

    @Test
    void 默认topK配置非法时抛异常() {
        var props = new MemoryRetrievalProperties();
        props.getOrchestrator().setDefaultTopK(0);
        var planner = planner(mock(HybridRetriever.class), mock(SemanticMemory.class));
        var orchestrator = new RetrievalOrchestrator(planner, props);

        assertThatThrownBy(() -> orchestrator.retrieve("test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("检索编排默认 topK必须大于 0");
    }

    @Test
    void 多source合并后限制topK() {
        var hybrid = mock(HybridRetriever.class);
        when(hybrid.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        retrievalResult("h1", "H1", 0.9f),
                        retrievalResult("h2", "H2", 0.7f)
                ));
        var sem = mock(SemanticMemory.class);
        when(sem.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(
                        experienceEntity("exp-1", "test content", "test 匹配")
                ));

        var planner = new QueryPlanner(
                new HybridRetrievalSource(hybrid),
                new ExperienceRetrievalSource(sem),
                emptyKnowledgeSource());
        var orchestrator = new RetrievalOrchestrator(planner, new MemoryRetrievalProperties());

        var bundle = orchestrator.retrieve("test", RetrievalIntent.GENERAL, 2);

        assertThat(bundle.items()).hasSize(2);
        assertThat(bundle.sources()).contains("hybrid", "experience");
    }

    @Test
    void adapter抛异常时直接暴露() {
        var hybrid = mock(HybridRetriever.class);
        when(hybrid.retrieve(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("hybrid 挂了"));
        var sem = mock(SemanticMemory.class);
        when(sem.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(experienceEntity("exp-1", "Rust 经验", "Rust 相关")));

        var planner = new QueryPlanner(
                new HybridRetrievalSource(hybrid),
                new ExperienceRetrievalSource(sem),
                emptyKnowledgeSource());
        var orchestrator = new RetrievalOrchestrator(planner, new MemoryRetrievalProperties());

        assertThatThrownBy(() -> orchestrator.retrieve("Rust", RetrievalIntent.GENERAL, 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("hybrid 挂了");
    }

    private QueryPlanner planner(HybridRetriever hybrid, SemanticMemory semanticMemory) {
        return new QueryPlanner(
                new HybridRetrievalSource(hybrid),
                new ExperienceRetrievalSource(semanticMemory),
                emptyKnowledgeSource());
    }

    private KnowledgeBaseSource emptyKnowledgeSource() {
        var kbRepository = mock(KnowledgeBaseRepository.class);
        when(kbRepository.findAll()).thenReturn(List.of());
        return new KnowledgeBaseSource(mock(DocumentRetriever.class), kbRepository);
    }

    private RetrievalResult retrievalResult(String id, String name, float score) {
        return new RetrievalResult(id, "GOAL", name, "描述", score,
                new RetrievalResult.ScoreBreakdown(score, score * 0.4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                "vector", null, 0.5f, null, false, false, false);
    }

    private TemporalEntity experienceEntity(String id, String name, String desc) {
        Instant now = Instant.now();
        return new TemporalEntity(
                id, EntityType.EXPERIENCE, name, desc,
                Map.of(), 1, true, now, null, null,
                0.8f, 0.6f, 0, null, now, now,
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
}
