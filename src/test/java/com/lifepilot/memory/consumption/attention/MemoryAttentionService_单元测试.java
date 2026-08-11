package com.lifepilot.memory.consumption.attention;

import com.lifepilot.memory.consumption.config.MemoryConsumptionProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryAttentionService 单元测试 —— 验证四类候选生成、排序、topN、kind 开关与失败暴露。
 *
 * @author zsg
 * @since 2026-06-07
 */
class MemoryAttentionService_单元测试 {

    // 锚定到真实当天起点（UTC）—— 避免固定过去日期与 isPromptConsumable 墙钟过期判断冲突的时间炸弹
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.DAYS);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 相对 NOW 的 yyyy-MM-dd 日期字符串（dueAt 用）。 */
    private static String isoPlus(long days) {
        return LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(days).toString();
    }

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
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of());
        when(semanticMemory.findCurrentByType(any(), any())).thenReturn(List.of());
        when(graphReasoner.connectionOpportunities(any(), any())).thenReturn(List.of());
        when(semanticMemory.existsConsumableById(any())).thenReturn(true);
        service = new MemoryAttentionService(semanticMemory, graphReasoner, properties, CLOCK);
    }

    /** 默认构造可消费实体（USER_CONFIRMED / EXPLICIT，trustScore 0.9，过质量门）。 */
    private TemporalEntity entity(String id, String name, EntityType type, float importance,
                                  Instant expiresAt, Instant lastAccessed, int version) {
        return new TemporalEntity(
                id, type, name, name + "描述", Map.of(), version, true, NOW, null, "conv",
                0.9f, importance, 0, lastAccessed, NOW, NOW,
                LifecycleState.ACTIVE, null, expiresAt, Temporality.PERSISTENT, null, false, List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        NOW)
                .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f, 1, NOW);
    }

    /** 构造不可消费实体（UNVERIFIED，trustScore 0）—— 用于验证质量门拦截。 */
    private TemporalEntity unverifiedEntity(String id, String name, EntityType type, float importance,
                                            Instant expiresAt, Instant lastAccessed, int version) {
        return new TemporalEntity(
                id, type, name, name + "描述", Map.of(), version, true, NOW, null, "conv",
                0.3f, importance, 0, lastAccessed, NOW, NOW,
                LifecycleState.ACTIVE, null, expiresAt, Temporality.PERSISTENT, null, false, List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        NOW)
                .withQuality(MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.UNVERIFIED, 0.0f, 0, null);
    }

    private TemporalEntity dueEntity(String id, String name, EntityType type, float importance, Object dueAt) {
        return new TemporalEntity(
                id, type, name, name + "描述", Map.of("dueAt", dueAt), 1, true, NOW, null, "conv",
                0.9f, importance, 0, NOW, NOW, NOW,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT, null, false, List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        NOW)
                .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f, 1, NOW);
    }

    @Test
    void DUE_SOON_未来窗口内应产出并标注剩余天数() {
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of(
                dueEntity("g1", "季度述职报告", EntityType.GOAL, 0.7f, isoPlus(3))));

        var items = service.computeAttention(null, 10);

        assertThat(items).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo(MemoryAttentionService.AttentionKind.DUE_SOON);
            assertThat(i.entityId()).isEqualTo("g1");
            assertThat(i.reason()).contains("天后到期");
            assertThat(i.dueAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        });
    }

    @Test
    void DUE_SOON_已逾期应产出并标注逾期() {
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of(
                dueEntity("g1", "季度述职报告", EntityType.GOAL, 0.7f, isoPlus(-6))));  // 已逾期

        var items = service.computeAttention(null, 10);

        assertThat(items).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo(MemoryAttentionService.AttentionKind.DUE_SOON);
            assertThat(i.reason()).contains("已逾期");
            assertThat(i.daysIdle()).isLessThan(0L);  // 逾期 daysUntil 为负
        });
    }

    @Test
    void DUE_SOON_窗口外不产出() {
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of(
                dueEntity("g1", "远期目标", EntityType.GOAL, 0.7f, isoPlus(86))));  // 远超 14 天窗口

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.DUE_SOON);
    }

    @Test
    void DUE_SOON_非法日期应直接暴露错误() {
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of(
                dueEntity("g1", "目标", EntityType.GOAL, 0.7f, "不是日期")));

        assertThatThrownBy(() -> service.computeAttention(null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注意力 dueAt 格式非法")
                .hasMessageContaining("g1")
                .hasMessageContaining("不是日期");
    }

    @Test
    void DUE_SOON_时间戳dueAt应直接暴露错误() {
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of(
                dueEntity("g1", "目标", EntityType.GOAL, 0.7f, "2026-06-13T00:00:00Z")));

        assertThatThrownBy(() -> service.computeAttention(null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注意力 dueAt 格式非法")
                .hasMessageContaining("g1")
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void 关闭DUE_SOON时不查询() {
        properties.getAttention().setDueSoonEnabled(false);
        service.computeAttention(null, 10);
        verify(semanticMemory, never()).findWithDueDate(any(), any());
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
    void 单类异常应直接抛出() {
        when(semanticMemory.findNeglected(any(), any(), org.mockito.ArgumentMatchers.anyFloat(), any()))
                .thenThrow(new RuntimeException("DB 故障"));
        when(semanticMemory.findApproachingExpiry(any(), any(), any())).thenReturn(List.of(
                entity("g1", "学小提琴", EntityType.GOAL, 0.9f, NOW.plus(Duration.ofDays(1)), NOW, 1)));

        assertThatThrownBy(() -> service.computeAttention(null, 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 故障");
    }

    @Test
    void 总开关关闭返回空() {
        properties.getAttention().setEnabled(false);
        assertThat(service.computeAttention(null, 10)).isEmpty();
    }

    @Test
    void 非法窗口配置应抛异常() {
        properties.getAttention().setExpiringWindowDays(0);

        assertThatThrownBy(() -> service.computeAttention(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPIRING 窗口天数必须大于 0");
    }

    @Test
    void 默认topN配置非法时应抛异常() {
        properties.getAttention().setTopN(0);

        assertThatThrownBy(() -> service.computeAttention(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("注意力返回上限必须大于 0");
    }

    // ── 质量门（Task 2 / 2.1）：不可信 / 已完成实体不应主动浮现 ──

    @Test
    void UNVERIFIED实体不应产出EXPIRING() {
        when(semanticMemory.findApproachingExpiry(any(), any(), any())).thenReturn(List.of(
                unverifiedEntity("g1", "猜测目标", EntityType.GOAL, 0.9f,
                        NOW.plus(Duration.ofDays(2)), NOW, 1)));

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.EXPIRING);
    }

    @Test
    void UNVERIFIED实体不应产出NEGLECTED() {
        when(semanticMemory.findNeglected(any(), any(), org.mockito.ArgumentMatchers.anyFloat(), any()))
                .thenReturn(List.of(
                        unverifiedEntity("p1", "猜测项目", EntityType.PROJECT, 0.9f,
                                null, NOW.minus(Duration.ofDays(60)), 1)));

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.NEGLECTED);
    }

    @Test
    void UNVERIFIED实体不应产出DUE_SOON() {
        var due = new TemporalEntity(
                "g1", EntityType.GOAL, "猜测截止", "描述", Map.of("dueAt", isoPlus(3)),
                1, true, NOW, null, "conv", 0.3f, 0.7f, 0, NOW, NOW, NOW,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT, null, false, List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        NOW)
                .withQuality(MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.UNVERIFIED, 0.0f, 0, null);
        when(semanticMemory.findWithDueDate(any(), any())).thenReturn(List.of(due));

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.DUE_SOON);
    }

    @Test
    void UNVERIFIED实体不应产出EVOLVING() {
        when(semanticMemory.findCurrentByType(eqType(EntityType.GOAL), any())).thenReturn(List.of(
                unverifiedEntity("g2", "猜测演进", EntityType.GOAL, 0.7f, null, NOW, 3)));

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.EVOLVING);
    }

    @Test
    void COMPLETED实体不应产出EVOLVING() {
        var completed = new TemporalEntity(
                "g3", EntityType.GOAL, "已完成目标", "描述", Map.of(), 3, true, NOW, null, "conv",
                0.9f, 0.7f, 0, NOW, NOW, NOW,
                LifecycleState.COMPLETED, null, null, Temporality.PERSISTENT, null, false, List.of(),
                        com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                        com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                        1.0f,
                        1,
                        NOW)
                .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f, 1, NOW);
        when(semanticMemory.findCurrentByType(eqType(EntityType.GOAL), any())).thenReturn(List.of(completed));

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.EVOLVING);
    }

    @Test
    void CONNECTION目标实体不可消费时不应产出() {
        when(semanticMemory.findApproachingExpiry(any(), any(), any())).thenReturn(List.of(
                entity("g1", "学小提琴", EntityType.GOAL, 0.9f, NOW.plus(Duration.ofDays(2)), NOW, 1)));
        when(graphReasoner.connectionOpportunities(any(), any())).thenReturn(List.of(
                new GraphReasoner.ConnectionOpportunity("g1", "b1", "杭州", "t1", "网易", "位于", "工作于", 0.8f)));
        when(semanticMemory.existsConsumableById("t1")).thenReturn(false);

        var items = service.computeAttention(null, 10);

        assertThat(items).noneMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.CONNECTION);
    }

    private static EntityType eqType(EntityType t) {
        return org.mockito.ArgumentMatchers.eq(t);
    }
}
