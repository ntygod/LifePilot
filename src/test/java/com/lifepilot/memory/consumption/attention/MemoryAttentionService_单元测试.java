package com.lifepilot.memory.consumption.attention;

import com.lifepilot.memory.consumption.config.MemoryConsumptionProperties;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryAttentionService 单元测试 —— 验证四类候选生成、排序、topN、kind 开关与故障隔离。
 *
 * @author zsg
 * @since 2026-06-07
 */
class MemoryAttentionService_单元测试 {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SemanticMemory semanticMemory;
    private GraphReasoner graphReasoner;
    private MemoryConsumptionProperties properties;
    private MemoryAttentionService service;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        graphReasoner = mock(GraphReasoner.class);
        properties = new MemoryConsumptionProperties();
        when(semanticMemory.findApproachingExpiry(any(), any(), any())).thenReturn(List.of());
        when(semanticMemory.findNeglected(any(), any(), org.mockito.ArgumentMatchers.anyFloat(), any()))
                .thenReturn(List.of());
        when(semanticMemory.findCurrentByType(any(), any())).thenReturn(List.of());
        when(graphReasoner.connectionOpportunities(any(), any())).thenReturn(List.of());
        service = new MemoryAttentionService(semanticMemory, graphReasoner, properties, CLOCK);
    }

    private TemporalEntity entity(String id, String name, EntityType type, float importance,
                                  Instant expiresAt, Instant lastAccessed, int version) {
        return new TemporalEntity(
                id, type, name, name + "描述", Map.of(), version, true, NOW, null, "conv",
                0.9f, importance, 0, lastAccessed, NOW, NOW,
                LifecycleState.ACTIVE, null, expiresAt, Temporality.PERSISTENT, null, false, List.of());
    }

    @Test
    void 四类候选都应生成并按score降序() {
        when(semanticMemory.findApproachingExpiry(any(), any(), any())).thenReturn(List.of(
                entity("g1", "学小提琴", EntityType.GOAL, 0.9f, NOW.plus(Duration.ofDays(2)), NOW, 1)));
        when(semanticMemory.findNeglected(any(), any(), org.mockito.ArgumentMatchers.anyFloat(), any()))
                .thenReturn(List.of(
                        entity("p1", "推荐系统项目", EntityType.PROJECT, 0.8f, null, NOW.minus(Duration.ofDays(60)), 1)));
        when(semanticMemory.findCurrentByType(eqType(EntityType.GOAL), any())).thenReturn(List.of(
                entity("g2", "学机器学习", EntityType.GOAL, 0.7f, null, NOW, 3)));
        when(graphReasoner.connectionOpportunities(any(), any())).thenReturn(List.of(
                new GraphReasoner.ConnectionOpportunity("g1", "b1", "杭州", "t1", "网易", "位于", "工作于", 0.8f)));

        var items = service.computeAttention(null, 10);

        assertThat(items).extracting(i -> i.kind().name())
                .contains("EXPIRING", "NEGLECTED", "EVOLVING", "CONNECTION");
        // 降序
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).score()).isGreaterThanOrEqualTo(items.get(i).score());
        }
    }

    @Test
    void 关闭EXPIRING时不产出也不查询() {
        properties.getAttention().setExpiringEnabled(false);

        service.computeAttention(null, 10);

        verify(semanticMemory, never()).findApproachingExpiry(any(), any(), any());
    }

    @Test
    void topN应截断结果() {
        when(semanticMemory.findNeglected(any(), any(), org.mockito.ArgumentMatchers.anyFloat(), any()))
                .thenReturn(List.of(
                        entity("p1", "项目A", EntityType.PROJECT, 0.9f, null, NOW.minus(Duration.ofDays(90)), 1),
                        entity("p2", "项目B", EntityType.PROJECT, 0.8f, null, NOW.minus(Duration.ofDays(80)), 1),
                        entity("p3", "项目C", EntityType.PROJECT, 0.7f, null, NOW.minus(Duration.ofDays(70)), 1)));

        var items = service.computeAttention(null, 2);

        assertThat(items).hasSize(2);
    }

    @Test
    void 单类异常应被隔离不影响其他类() {
        when(semanticMemory.findNeglected(any(), any(), org.mockito.ArgumentMatchers.anyFloat(), any()))
                .thenThrow(new RuntimeException("DB 故障"));
        when(semanticMemory.findApproachingExpiry(any(), any(), any())).thenReturn(List.of(
                entity("g1", "学小提琴", EntityType.GOAL, 0.9f, NOW.plus(Duration.ofDays(1)), NOW, 1)));

        var items = service.computeAttention(null, 10);

        assertThat(items).anyMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.EXPIRING);
    }

    @Test
    void 总开关关闭返回空() {
        properties.getAttention().setEnabled(false);
        assertThat(service.computeAttention(null, 10)).isEmpty();
    }

    private static EntityType eqType(EntityType t) {
        return org.mockito.ArgumentMatchers.eq(t);
    }
}
