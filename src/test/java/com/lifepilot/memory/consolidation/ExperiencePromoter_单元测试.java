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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                now);
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(proceduralMemory.findById("exp-1")).thenReturn(Optional.empty());

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int promoted = promoter.promote();

        assertThat(promoted).isEqualTo(1);
        verify(proceduralMemory).save(any(ProcedureTemplate.class));
        verify(semanticMemory, never()).archive(any(TemporalEntity.class), any());
    }

    @Test
    @DisplayName("已存在同名模板时跳过提升")
    void 已存在模板时跳过提升() {
        var properties = new AgentLearningProperties();
        var semanticMemory = mock(SemanticMemory.class);
        var proceduralMemory = mock(ProceduralMemory.class);
        var now = Instant.parse("2026-05-07T06:30:00Z");
        var experience = new TemporalEntity(
                "exp-1", EntityType.EXPERIENCE, "经验名", "经验描述", Map.of(),
                1, true, now, null, "session-1", 1.0f, 0.95f, 3, now, now, now);
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(proceduralMemory.findById("exp-1")).thenReturn(Optional.of(mock(ProcedureTemplate.class)));

        var promoter = new ExperiencePromoter(semanticMemory, proceduralMemory, properties);
        int promoted = promoter.promote();

        assertThat(promoted).isZero();
        verify(proceduralMemory, never()).save(any(ProcedureTemplate.class));
    }
}
