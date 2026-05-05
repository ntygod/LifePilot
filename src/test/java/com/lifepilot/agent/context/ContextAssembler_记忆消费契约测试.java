package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.quality.MemoryEvidenceKind;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 记忆消费契约测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("ContextAssembler 记忆消费契约")
class ContextAssembler_记忆消费契约测试 {

    private SemanticMemory semanticMemory;
    private HybridRetriever hybridRetriever;
    private ContextAssembler assembler;

    @BeforeEach
    void 初始化() {
        semanticMemory = mock(SemanticMemory.class);
        hybridRetriever = mock(HybridRetriever.class);
        assembler = new ContextAssembler(
                new AgentConfigProperties(),
                mock(PromptRegistry.class),
                null,
                semanticMemory,
                new MemoryProperties(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                hybridRetriever);
    }

    @Test
    void memoryContext只返回可注入质量的实体() {
        var verified = 实体("verified-1", "可信主题")
                .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f, 1, Instant.now());
        var unverified = 实体("unverified-1", "未知主题");
        var filter = MemoryReadFilter.userMemory();

        when(hybridRetriever.retrieve(eq("主题"), anyInt(), any(RetrievalWeights.class), eq(filter)))
                .thenReturn(List.of(
                        检索结果(verified.id(), EntityType.TOPIC, 0.8f),
                        检索结果(unverified.id(), EntityType.TOPIC, 0.9f)));
        when(semanticMemory.findByIds(any(), eq(filter)))
                .thenReturn(Map.of(verified.id(), verified, unverified.id(), unverified));

        var result = assembler.safeRetrieveRelevantMemories("主题", filter);

        assertThat(result).containsExactly(verified);
    }

    @Test
    void 格式化memoryContext时只记录实际进入prompt的实体Id() {
        var verified = 实体("verified-2", "可信事实")
                .withQuality(MemoryEvidenceKind.DOCUMENT_GROUNDED, MemoryTrustLevel.VERIFIED, 0.86f, 1, Instant.now());
        var unverified = 实体("unverified-2", "未知事实");

        var section = assembler.formatMemorySection(List.of(verified, unverified));

        assertThat(section.text()).contains("可信事实")
                .contains("VERIFIED/DOCUMENT_GROUNDED")
                .doesNotContain("未知事实");
        assertThat(section.entityIds()).containsExactly(verified.id());
    }

    @Test
    void userProfile优先使用巩固画像且必须满足可消费门槛并记录实体Id() {
        var profile = new TemporalEntity(
                "profile-1",
                EntityType.CUSTOM,
                "__consolidated_profile",
                "稳定画像文本",
                Map.of(),
                1,
                true,
                Instant.parse("2026-05-05T00:00:00Z"),
                null,
                "session-1",
                0.8f,
                0.6f,
                0,
                null,
                Instant.parse("2026-05-05T00:00:00Z"),
                Instant.parse("2026-05-05T00:00:00Z")
        ).withQuality(MemoryEvidenceKind.DERIVED, MemoryTrustLevel.DERIVED, 0.7f, 1, Instant.now());

        when(semanticMemory.findCurrentByNameAndType(eq("__consolidated_profile"), eq(EntityType.CUSTOM), any()))
                .thenReturn(java.util.Optional.of(profile));

        var section = assembler.safeGetUserProfile("随便", MemoryReadFilter.userProfile());

        assertThat(section.text()).contains("巩固用户画像");
        assertThat(section.entityIds()).containsExactly("profile-1");
    }

    private TemporalEntity 实体(String id, String name) {
        var now = Instant.parse("2026-05-05T00:00:00Z");
        return new TemporalEntity(
                id,
                EntityType.TOPIC,
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
                new RetrievalResult.ScoreBreakdown(score, score, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                "vector",
                null,
                0.7f,
                null,
                false,
                false,
                false);
    }
}
