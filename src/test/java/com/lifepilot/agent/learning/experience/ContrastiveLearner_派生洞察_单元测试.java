package com.lifepilot.agent.learning.experience;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContrastiveLearner 派生洞察单元测试（记忆链路 #4）。
 *
 * <p>验证对比学习产出独立的派生 EXPERIENCE 实体（isDerived + derivationSources=[success,failure]），
 * 而非原地增强源经验。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
class ContrastiveLearner_派生洞察_单元测试 {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    private SemanticMemory semanticMemory;
    private VectorSearcher vectorSearcher;
    private GenerationRouter generationRouter;
    private PromptRegistry promptRegistry;
    private ContrastiveLearner learner;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        vectorSearcher = mock(VectorSearcher.class);
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        var props = new AgentLearningProperties();
        props.getExperience().getContrastive().setEnabled(true);
        learner = new ContrastiveLearner(semanticMemory, vectorSearcher, generationRouter, promptRegistry, props);
    }

    private TemporalEntity experience(String id, String name, boolean success) {
        return experience(id, name, Map.of("success", success));
    }

    private TemporalEntity experience(String id, String name, Map<String, Object> properties) {
        var props = new LinkedHashMap<String, Object>();
        props.put("lessons", List.of("先校验输入"));
        props.put("toolsUsed", List.of("tool.test"));
        props.putAll(properties);
        return new TemporalEntity(
                id, EntityType.EXPERIENCE, name, name + "描述", props,
                1, true, NOW, null, "conv", 0.8f, 0.7f, 0, null, NOW, NOW,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT, null, false, List.of(),
                MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 1.0f, 1, NOW);
    }

    @Test
    void 对比学习应产出带血缘的派生洞察实体() {
        var success = experience("exp-success", "成功路径", true);
        var failure = experience("exp-failure", "失败路径", false);

        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("exp-failure", 0.9f)));
        when(semanticMemory.findById("exp-failure")).thenReturn(Optional.of(failure));
        when(promptRegistry.render(any(), any())).thenReturn("prompt");
        var resp = mock(LlmResponse.class);
        when(resp.content()).thenReturn(
                "{\"failureReason\":\"没有先验证输入\",\"successFactor\":\"先校验再执行\",\"contrastiveLessons\":[\"动手前先校验输入\"]}");
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        learner.learn(success);

        ArgumentCaptor<TemporalEntity> captor = ArgumentCaptor.forClass(TemporalEntity.class);
        verify(semanticMemory).upsertWithConflictDetection(
                captor.capture(),
                eq("contrastive-learning"),
                any(MemoryWriteContext.class));
        var derived = captor.getValue();

        assertThat(derived.isDerived()).isTrue();
        assertThat(derived.derivationSources()).containsExactlyInAnyOrder("exp-success", "exp-failure");
        assertThat(derived.type()).isEqualTo(EntityType.EXPERIENCE);
        assertThat(derived.properties()).containsEntry("insightType", "CONTRASTIVE");
        assertThat(derived.id()).isNotEqualTo("exp-success");  // 不复用源 id（非原地增强）
    }

    @Test
    void 未找到相反success经验时不产出洞察() {
        var success = experience("exp-success", "成功路径", true);
        // 只返回同 success 标志的经验 → 无对比对
        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("exp-other", 0.9f)));
        when(semanticMemory.findById("exp-other"))
                .thenReturn(Optional.of(experience("exp-other", "另一成功", true)));

        learner.learn(success);

        verify(semanticMemory, org.mockito.Mockito.never())
                .upsertWithConflictDetection(any(), any(String.class), any(MemoryWriteContext.class));
    }

    @Test
    void 新经验缺success属性时直接暴露() {
        var experience = experience("exp-new", "新经验", Map.of());

        assertThatThrownBy(() -> learner.learn(experience))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPERIENCE success 必须是 boolean");
    }

    @Test
    void 向量搜索返回null时直接暴露() {
        var success = experience("exp-success", "成功路径", true);
        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenReturn(null);

        assertThatThrownBy(() -> learner.learn(success))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对比学习: 向量搜索结果不能为空");
    }

    @Test
    void 向量候选实体不存在时直接暴露() {
        var success = experience("exp-success", "成功路径", true);
        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("exp-missing", 0.9f)));
        when(semanticMemory.findById("exp-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learner.learn(success))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("对比学习: 向量候选实体不存在");
    }

    @Test
    void 候选经验缺success属性时直接暴露() {
        var success = experience("exp-success", "成功路径", true);
        var badCandidate = experience("exp-bad", "坏候选", Map.of());
        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("exp-bad", 0.9f)));
        when(semanticMemory.findById("exp-bad")).thenReturn(Optional.of(badCandidate));

        assertThatThrownBy(() -> learner.learn(success))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPERIENCE success 必须是 boolean");
    }

    @Test
    void GenerationRouter缺失时构造失败() {
        var props = new AgentLearningProperties();

        assertThatThrownBy(() -> new ContrastiveLearner(
                semanticMemory, vectorSearcher, null, promptRegistry, props))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("generationRouter 不能为空");
    }

    @Test
    void LLM响应缺少必要字段时暴露异常() {
        var success = experience("exp-success", "成功路径", true);
        var failure = experience("exp-failure", "失败路径", false);

        when(vectorSearcher.searchEntities(any(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("exp-failure", 0.9f)));
        when(semanticMemory.findById("exp-failure")).thenReturn(Optional.of(failure));
        when(promptRegistry.render(any(), any())).thenReturn("prompt");
        var resp = mock(LlmResponse.class);
        when(resp.content()).thenReturn("{\"failureReason\":\"\",\"successFactor\":\"先校验再执行\"}");
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any())).thenReturn(resp);

        assertThatThrownBy(() -> learner.learn(success))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failureReason 或 successFactor");

        verify(semanticMemory, org.mockito.Mockito.never())
                .upsertWithConflictDetection(any(), any(String.class), any(MemoryWriteContext.class));
    }
}
