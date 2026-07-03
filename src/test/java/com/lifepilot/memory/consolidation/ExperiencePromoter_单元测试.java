package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.agent.learning.consolidation.ExperiencePromoter;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExperiencePromoter 单元测试 — 验证高频经验提升为 L4 ProcedureTemplate。
 *
 * <p>逻辑从 ConsolidationPipeline 私有方法抽取而来，保留原"提升后不立即归档源实体"语义。</p>
 *
 * @author zsg
 * @since 2026-06-05
 */
@DisplayName("ExperiencePromoter 经验提升单元测试")
class ExperiencePromoter_单元测试 {

    @Test
    @DisplayName("达到阈值的高频经验提升为模板且不归档源实体")
    void 经验提升为模板后不应立即归档源实体() {
        var properties = new AgentLearningProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var proceduralMemory = mock(ProceduralMemory.class);
        var now = Instant.parse("2026-05-07T06:30:00Z");
        var experience = new TemporalEntity(
                "exp-1",
                EntityType.EXPERIENCE,
                "取消带编号的提醒记忆",
                "用编号和描述关键词组合搜索后取消。",
                Map.of(),
                1,
                true,
                now,
                null,
                "session-1",
                1.0f,
                0.95f,
                3,
                now,
                now,
                now,
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
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(proceduralMemory.findBySourceEntityId("exp-1")).thenReturn(Optional.empty());

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int promoted = promoter.promote();

        assertThat(promoted).isEqualTo(1);
        verify(proceduralMemory).save(any(ProcedureTemplate.class));
        verify(semanticMemory, never()).archive(any(TemporalEntity.class), any());
    }

    @Test
    @DisplayName("提升模板的 useCount 反映源经验 accessCount 且立即可靠")
    void 提升模板useCount应反映源经验accessCount且可靠() {
        var properties = new AgentLearningProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var proceduralMemory = mock(ProceduralMemory.class);
        var now = Instant.parse("2026-05-07T06:30:00Z");
        // accessCount=5（>= minAccessCount 默认 3），importanceScore=0.95（>= 阈值）
        var experience = new TemporalEntity(
                "exp-1", EntityType.EXPERIENCE, "经验名", "经验描述", Map.of(),
                1, true, now, null, "session-1", 1.0f, 0.95f, 5, now, now, now,
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
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(proceduralMemory.findBySourceEntityId("exp-1")).thenReturn(Optional.empty());

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int promoted = promoter.promote();

        assertThat(promoted).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(ProcedureTemplate.class);
        verify(proceduralMemory).save(captor.capture());
        var saved = captor.getValue();
        // useCount 由源经验 accessCount 驱动，successRate 维持 1.0
        assertThat(saved.useCount()).isEqualTo(5);
        assertThat(saved.successRate()).isEqualTo(1.0f);
        // 配合 IntentMatcher 默认阈值（minReliability=0.7, minUseCount=2）应立即可靠
        assertThat(saved.isReliable(0.7f, 2)).isTrue();
    }

    @Test
    @DisplayName("已存在同源模板时跳过提升（按 sourceEntityId 去重）")
    void 已存在模板时跳过提升() {
        var properties = new AgentLearningProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var proceduralMemory = mock(ProceduralMemory.class);
        var now = Instant.parse("2026-05-07T06:30:00Z");
        var experience = new TemporalEntity(
                "exp-1", EntityType.EXPERIENCE, "经验名", "经验描述", Map.of(),
                1, true, now, null, "session-1", 1.0f, 0.95f, 3, now, now, now,
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
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(proceduralMemory.findBySourceEntityId("exp-1")).thenReturn(Optional.of(mock(ProcedureTemplate.class)));

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int promoted = promoter.promote();

        assertThat(promoted).isZero();
        verify(proceduralMemory, never()).save(any(ProcedureTemplate.class));
    }

    @Test
    @DisplayName("连续两次提升同一经验仅保存一次（按 sourceEntityId 去重幂等）")
    void 重复提升同一经验应幂等仅保存一次() {
        var properties = new AgentLearningProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var proceduralMemory = mock(ProceduralMemory.class);
        var now = Instant.parse("2026-05-07T06:30:00Z");
        var experience = new TemporalEntity(
                "exp-1", EntityType.EXPERIENCE, "经验名", "经验描述", Map.of(),
                1, true, now, null, "session-1", 1.0f, 0.95f, 4, now, now, now,
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
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));

        // 模拟去重的真实时序：首次提升前查无现存模板（保存）；保存后再次提升时
        // findBySourceEntityId 命中现存活跃模板 → 跳过。验证 save 恰好仅执行一次。
        var saved = new java.util.concurrent.atomic.AtomicReference<ProcedureTemplate>();
        when(proceduralMemory.findBySourceEntityId("exp-1"))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(proceduralMemory).save(any(ProcedureTemplate.class));

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int first = promoter.promote();
        int second = promoter.promote();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        verify(proceduralMemory, times(1)).save(any(ProcedureTemplate.class));
    }

    @Test
    @DisplayName("提升模板使用独立 templateId 且 sourceEntityId 指向源经验，避免向量串号")
    void 提升模板templateId应独立且sourceEntityId指向源经验() {
        var properties = new AgentLearningProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var proceduralMemory = mock(ProceduralMemory.class);
        var now = Instant.parse("2026-05-07T06:30:00Z");
        var experience = new TemporalEntity(
                "exp-1", EntityType.EXPERIENCE, "经验名", "经验描述", Map.of(),
                1, true, now, null, "session-1", 1.0f, 0.95f, 3, now, now, now,
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
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(proceduralMemory.findBySourceEntityId("exp-1")).thenReturn(Optional.empty());

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int promoted = promoter.promote();

        assertThat(promoted).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(ProcedureTemplate.class);
        verify(proceduralMemory).save(captor.capture());
        var saved = captor.getValue();
        // templateId 为独立 UUID，不复用 exp.id()，避免与源 EXPERIENCE 实体向量串号
        assertThat(saved.templateId()).isNotEqualTo("exp-1");
        // sourceEntityId 指向源 L3 EXPERIENCE 实体 id，供去重与 L4 级联失活
        assertThat(saved.sourceEntityId()).isEqualTo("exp-1");
    }
}
