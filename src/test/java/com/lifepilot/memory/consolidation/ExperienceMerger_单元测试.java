package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ExperienceMerger;
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
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExperienceMerger 单元测试。
 *
 * @author zsg
 * @since 2026-06-30
 */
@DisplayName("ExperienceMerger 单元测试")
class ExperienceMerger_单元测试 {

    @Test
    @DisplayName("EXPERIENCE缺success属性应直接暴露")
    void EXPERIENCE缺success属性应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var merger = merger(semanticMemory, mock(VectorSearcher.class),
                mock(GenerationRouter.class), mock(PromptRegistry.class));
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(
                        entity("a", "经验 A", Map.of("success", true)),
                        entity("b", "经验 B", Map.of())));

        assertThatThrownBy(merger::merge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPERIENCE success 必须是 boolean");
    }

    @Test
    @DisplayName("向量搜索返回null应直接暴露")
    void 向量搜索返回null应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var merger = merger(semanticMemory, vectorSearcher,
                mock(GenerationRouter.class), mock(PromptRegistry.class));
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(
                        entity("a", "经验 A", Map.of("success", true)),
                        entity("b", "经验 B", Map.of("success", true))));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenReturn(null);

        assertThatThrownBy(merger::merge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("经验合并: 向量搜索结果不能为空");
    }

    @Test
    @DisplayName("LLM合并响应缺success应直接暴露")
    void LLM合并响应缺success应直接暴露() {
        var semanticMemory = mock(SemanticMemory.class);
        var vectorSearcher = mock(VectorSearcher.class);
        var generationRouter = mock(GenerationRouter.class);
        var promptRegistry = mock(PromptRegistry.class);
        var merger = merger(semanticMemory, vectorSearcher, generationRouter, promptRegistry);
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                .thenReturn(List.of(
                        entity("a", "经验 A", Map.of("success", true)),
                        entity("b", "经验 B", Map.of("success", true))));
        when(vectorSearcher.searchEntities(anyString(), eq(5), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult("b", 0.95f)),
                        List.of(new VectorSearchResult("a", 0.95f)));
        when(promptRegistry.render(anyString(), any())).thenReturn("merge-prompt");
        when(generationRouter.call(any(), anyString(), any(), any(), any(),
                eq(GenerationCapability.CHAT), any()))
                .thenReturn(LlmResponse.cached("""
                        {
                          "scenario": "通用策略",
                          "strategy": "先拆解目标再执行"
                        }
                        """, "test", "test-model"));

        assertThatThrownBy(merger::merge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("经验合并 LLM 响应 success 必须是 boolean");
    }

    private static ExperienceMerger merger(SemanticMemory semanticMemory,
                                           VectorSearcher vectorSearcher,
                                           GenerationRouter generationRouter,
                                           PromptRegistry promptRegistry) {
        return new ExperienceMerger(
                semanticMemory,
                vectorSearcher,
                generationRouter,
                promptRegistry,
                new AgentLearningProperties());
    }

    private static TemporalEntity entity(String id, String name, Map<String, Object> properties) {
        var now = Instant.parse("2026-06-30T00:00:00Z");
        var props = new LinkedHashMap<String, Object>();
        props.put("lessons", List.of("先拆解目标"));
        props.put("toolsUsed", List.of("tool.test"));
        props.putAll(properties);
        return new TemporalEntity(
                id,
                EntityType.EXPERIENCE,
                name,
                "描述 " + name,
                props,
                1,
                true,
                now,
                null,
                "session-1",
                0.9f,
                0.7f,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                MemoryEvidenceKind.USER_CONFIRMED,
                MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                now);
    }
}
