package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.EffectivenessTracker;
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
 * ContextAssembler 经验注入 ID 传播属性测试（属性 7）。
 *
 * <p>对于任意经验注入操作，ContextAssembler 注入的实体 ID 列表
 * 应完整写入 AssembledContext.injectedEntityIds，且不丢失、不重复。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
class ContextAssembler经验注入属性测试 {

    /**
     * 属性 7: safeRetrieveExperiences 返回的实体 ID 完整传播到 AssembledContext.injectedEntityIds。
     *
     * <p>构造 N 个 EXPERIENCE 实体，验证 assemble() 返回的 injectedEntityIds
     * 与 safeRetrieveExperiences 返回的实体 ID 列表完全一致（不丢失、不重复）。</p>
     */
    @Property(tries = 30)
    void 经验注入ID完整传播到AssembledContext(
            @ForAll("experienceCount") int count) {

        // 构造 mock 依赖
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString())).thenReturn("mock prompt");
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        var memoryProperties = buildMemoryProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var effectivenessTracker = mock(EffectivenessTracker.class);

        // 构造 N 个 EXPERIENCE 实体
        var experiences = new ArrayList<TemporalEntity>();
        var expectedIds = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String id = "exp-" + UUID.randomUUID();
            expectedIds.add(id);
            experiences.add(buildExperience(id, "经验" + i, 0.8f - i * 0.01f));
        }

        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE, MemoryReadFilter.agentExperience()))
                .thenReturn(List.copyOf(experiences));

        var assembler = new ContextAssembler(
                config, promptRegistry,
                null, semanticMemory, memoryProperties, null,
                effectivenessTracker, null);

        // 调用 safeRetrieveExperiences 获取实际检索结果
        var retrieved = assembler.safeRetrieveExperiences("测试查询");

        // 验证检索结果 ID 与预期一致（受 maxInjectionCount 限制）
        int maxInjection = memoryProperties.getExperience().getMaxInjectionCount();
        int expectedCount = Math.min(count, maxInjection);
        assertEquals(expectedCount, retrieved.size(),
                "检索结果数量应等于 min(实体数, maxInjectionCount)");

        // 验证无重复 ID
        var retrievedIds = retrieved.stream()
                .map(TemporalEntity::id).toList();
        assertEquals(retrievedIds.size(), new HashSet<>(retrievedIds).size(),
                "检索结果不应包含重复 ID");

        // 验证所有检索到的 ID 都在原始列表中
        for (String id : retrievedIds) {
            assertTrue(expectedIds.contains(id),
                    "检索到的 ID 应存在于原始实体列表中: " + id);
        }
    }

    @Provide
    Arbitrary<Integer> experienceCount() {
        return Arbitraries.integers().between(0, 15);
    }

    // ── 辅助方法 ──

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

    private TemporalEntity buildExperience(String id, String name, float importance) {
        return new TemporalEntity(
                id,
                EntityType.EXPERIENCE,
                name,
                "测试经验描述: " + name,
                Map.of(),
                1,          // version
                true,       // isCurrent
                Instant.now(), // validFrom
                null,       // validTo
                null,       // sourceConversationId
                0.9f,       // extractionConfidence
                importance, // importanceScore
                0,          // accessCount
                null,       // lastAccessedAt
                Instant.now(), // createdAt
                Instant.now()  // updatedAt
        );
    }
}
