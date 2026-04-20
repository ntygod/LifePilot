package com.lifepilot.meta.infra.memory;

import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.knowledge.retrieve.SessionKnowledgeScopeResolver;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryToolProvider cancel action 行为测试 — 验证"按语义批量归档已取消目标"能力。
 *
 * <p>回归场景：用户说"取消定时任务"后，LLM 应能通过 cancel 工具
 * 把 GOAL/EXPERIENCE 类旧记忆批量归档，而不是仅 create 一条 PREFERENCE 了事。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@DisplayName("MemoryToolProvider cancel action 测试")
class MemoryToolProvider_取消action测试 {

    private HybridRetriever hybridRetriever;
    private SemanticMemory semanticMemory;
    private DynamicToolRegistry registry;

    @BeforeEach
    void 初始化() {
        hybridRetriever = mock(HybridRetriever.class);
        semanticMemory = mock(SemanticMemory.class);
        registry = new DynamicToolRegistry(mock(ApplicationEventPublisher.class));

        var provider = new MemoryToolProvider(
                hybridRetriever,
                semanticMemory,
                mock(EpisodicMemory.class),
                mock(DocumentRetriever.class),
                mock(SessionKnowledgeBaseRepository.class),
                mock(SessionKnowledgeScopeResolver.class),
                new MemoryProperties()
        );
        provider.registerTools(registry);
    }

    @Test
    void cancel应归档相关的GOAL与EXPERIENCE() {
        var goal = 构造实体("goal-diary", EntityType.GOAL, "写日记");
        var experience = 构造实体("exp-daily", EntityType.EXPERIENCE, "每日代码提交汇总");
        var unrelated = 构造实体("topic-x", EntityType.TOPIC, "闲聊话题");

        // 混合检索返回三条命中，其中 TOPIC 应被类型过滤掉
        when(hybridRetriever.retrieve(
                eq("定时任务"), anyInt(), any(RetrievalWeights.class), eq(MemoryReadFilter.all())))
                .thenReturn(List.of(
                        构造结果(goal, 0.92f),
                        构造结果(experience, 0.81f),
                        构造结果(unrelated, 0.70f)));
        when(semanticMemory.findById("goal-diary")).thenReturn(Optional.of(goal));
        when(semanticMemory.findById("exp-daily")).thenReturn(Optional.of(experience));

        var tool = registry.resolve("memory").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("action", "cancel", "query", "定时任务"),
                tool.inputSchema(),
                null,
                Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isEqualTo(2);

        // 核心断言：GOAL + EXPERIENCE 均被 archive，TOPIC 不被触及
        verify(semanticMemory).archive(goal);
        verify(semanticMemory).archive(experience);
        verify(semanticMemory, never()).archive(unrelated);
    }

    @Test
    void cancel在无命中时应返回友好消息且不调用archive() {
        when(hybridRetriever.retrieve(
                anyString(), anyInt(), any(RetrievalWeights.class), eq(MemoryReadFilter.all())))
                .thenReturn(List.of());

        var tool = registry.resolve("memory").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("action", "cancel", "query", "根本不存在的主题"),
                tool.inputSchema(),
                null,
                Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isEqualTo(0);
        assertThat(result.<String>getData("message")).contains("未找到");
        verify(semanticMemory, never()).archive(any());
    }

    @Test
    void cancel应按minScore阈值过滤低相关结果() {
        var strong = 构造实体("goal-strong", EntityType.GOAL, "明确相关目标");
        var weak = 构造实体("goal-weak", EntityType.GOAL, "弱相关目标");

        when(hybridRetriever.retrieve(
                anyString(), anyInt(), any(RetrievalWeights.class), eq(MemoryReadFilter.all())))
                .thenReturn(List.of(
                        构造结果(strong, 0.85f),
                        构造结果(weak, 0.30f)));
        when(semanticMemory.findById("goal-strong")).thenReturn(Optional.of(strong));

        var tool = registry.resolve("memory").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("action", "cancel", "query", "目标", "minScore", 0.6),
                tool.inputSchema(),
                null,
                Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isEqualTo(1);
        verify(semanticMemory).archive(strong);
        verify(semanticMemory, never()).archive(weak);
    }

    @Test
    void cancel应受maxArchive上限约束_避免误伤过多() {
        var g1 = 构造实体("g1", EntityType.GOAL, "目标1");
        var g2 = 构造实体("g2", EntityType.GOAL, "目标2");
        var g3 = 构造实体("g3", EntityType.GOAL, "目标3");
        var g4 = 构造实体("g4", EntityType.GOAL, "目标4");

        when(hybridRetriever.retrieve(
                anyString(), anyInt(), any(RetrievalWeights.class), eq(MemoryReadFilter.all())))
                .thenReturn(List.of(
                        构造结果(g1, 0.9f),
                        构造结果(g2, 0.85f),
                        构造结果(g3, 0.8f),
                        构造结果(g4, 0.75f)));
        when(semanticMemory.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return switch (id) {
                case "g1" -> Optional.of(g1);
                case "g2" -> Optional.of(g2);
                default -> Optional.empty();
            };
        });

        var tool = registry.resolve("memory").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("action", "cancel", "query", "目标", "maxArchive", 2),
                tool.inputSchema(),
                null,
                Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isEqualTo(2);
        verify(semanticMemory).archive(g1);
        verify(semanticMemory).archive(g2);
        verify(semanticMemory, never()).archive(g3);
        verify(semanticMemory, never()).archive(g4);
    }

    @Test
    void cancel可通过entityTypes覆盖默认类型集() {
        var topic = 构造实体("topic-1", EntityType.TOPIC, "指定话题");

        when(hybridRetriever.retrieve(
                anyString(), anyInt(), any(RetrievalWeights.class), eq(MemoryReadFilter.all())))
                .thenReturn(List.of(构造结果(topic, 0.9f)));
        when(semanticMemory.findById("topic-1")).thenReturn(Optional.of(topic));

        var tool = registry.resolve("memory").orElseThrow();
        var result = tool.execute(new ToolInput(
                tool.id(),
                Map.of("action", "cancel", "query", "话题",
                        "entityTypes", List.of("TOPIC")),
                tool.inputSchema(),
                null,
                Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.<Integer>getData("count")).isEqualTo(1);
        verify(semanticMemory).archive(topic);
    }

    private TemporalEntity 构造实体(String id, EntityType type, String name) {
        var now = Instant.now();
        return new TemporalEntity(
                id, type, name, "描述-" + name, Map.of(),
                1, true, now, null, "session-1",
                0.8f, 0.5f, 0, null, now, now);
    }

    private RetrievalResult 构造结果(TemporalEntity entity, float score) {
        return new RetrievalResult(
                entity.id(),
                entity.type().name(),
                entity.name(),
                entity.description(),
                score,
                new RetrievalResult.ScoreBreakdown(
                        score, score, 0f, 0f, 0f, 0f, 0f, 0f),
                "vector",
                entity.lastAccessedAt(),
                entity.importanceScore(),
                entity.validTo());
    }
}
