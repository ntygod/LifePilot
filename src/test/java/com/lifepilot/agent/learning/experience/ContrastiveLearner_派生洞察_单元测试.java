package com.lifepilot.agent.learning.experience;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        return new TemporalEntity(
                id, EntityType.EXPERIENCE, name, name + "描述", Map.of("success", success),
                1, true, NOW, null, "conv", 0.8f, 0.7f, 0, null, NOW, NOW,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT, null, false, List.of());
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
        verify(semanticMemory).upsertWithConflictDetection(captor.capture(), eq("contrastive-learning"));
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
                .upsertWithConflictDetection(any(), any(String.class));
    }
}
