package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.EffectivenessTracker;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import net.jqwik.api.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContextAssembler 经验注入 ID 传播属性测试。
 *
 * <p>对于任意经验注入操作，ContextAssembler 经语义检索 + 工具维度加权后返回的
 * 实体 ID 列表应不丢失、不重复，且数量受 maxInjectionCount 限制。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
class ContextAssembler经验注入属性测试 {

    @Property(tries = 30)
    void 经验检索ID完整且无重复(
            @ForAll("experienceCount") int count) {

        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString())).thenReturn("mock prompt");
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        var memoryProperties = buildMemoryProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var effectivenessTracker = mock(EffectivenessTracker.class);
        var hybridRetriever = mock(HybridRetriever.class);

        // 构造 N 个 EXPERIENCE 实体 + 对应的 RetrievalResult（按 fusedScore 降序）
        var entityMap = new LinkedHashMap<String, TemporalEntity>();
        var results = new ArrayList<RetrievalResult>();
        var expectedIds = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String id = "exp-" + UUID.randomUUID();
            expectedIds.add(id);
            entityMap.put(id, buildExperience(id, "经验" + i));
            float score = 0.9f - i * 0.01f;
            results.add(new RetrievalResult(
                    id, EntityType.EXPERIENCE.name(), "经验" + i, "测试经验",
                    score,
                    new RetrievalResult.ScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0),
                    "/path", null, 0.8f, null));
        }

        when(hybridRetriever.retrieve(anyString(), anyInt(), any(RetrievalWeights.class), any(MemoryReadFilter.class)))
                .thenReturn(results);
        when(semanticMemory.findByIds(any(Set.class), any(MemoryReadFilter.class)))
                .thenReturn(entityMap);

        var assembler = new ContextAssembler(
                config, promptRegistry,
                null, semanticMemory, memoryProperties, null,
                effectivenessTracker, null,
                null, null, null, null, null, null, null, null,
                hybridRetriever);

        var retrieved = assembler.safeRetrieveExperiences(
                "测试查询", MemoryReadFilter.agentExperience(), Set.of());

        int maxInjection = memoryProperties.getExperience().getMaxInjectionCount();
        int expectedCount = Math.min(count, maxInjection);
        assertEquals(expectedCount, retrieved.size(),
                "检索结果数量应等于 min(实体数, maxInjectionCount)");

        var retrievedIds = retrieved.stream().map(TemporalEntity::id).toList();
        assertEquals(retrievedIds.size(), new HashSet<>(retrievedIds).size(),
                "检索结果不应包含重复 ID");

        for (String id : retrievedIds) {
            assertTrue(expectedIds.contains(id),
                    "检索到的 ID 应存在于原始实体列表中: " + id);
        }
    }

    @Provide
    Arbitrary<Integer> experienceCount() {
        return Arbitraries.integers().between(0, 15);
    }

    private AgentConfigProperties buildConfig() {
        var config = new AgentConfigProperties();
        var context = new AgentConfigProperties.ContextConfig();
        context.setMaxContextTokens(8000);
        config.setContext(context);
        return config;
    }

    private MemoryProperties buildMemoryProperties() {
        var props = new MemoryProperties();
        var experience = new MemoryProperties.Experience();
        experience.setEnabled(true);
        experience.setMaxInjectionCount(5);
        experience.setInjectionTokenBudget(500);
        var isolation = new MemoryProperties.Experience.Isolation();
        isolation.setCrossContextRetrieval(true);
        experience.setIsolation(isolation);
        props.setExperience(experience);
        return props;
    }

    private TemporalEntity buildExperience(String id, String name) {
        return new TemporalEntity(
                id,
                EntityType.EXPERIENCE,
                name,
                "测试经验描述: " + name,
                Map.of(),
                1, true, Instant.now(), null, null,
                0.9f, 0.8f, 0, null, Instant.now(), Instant.now());
    }
}
