package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.consolidation.EpisodicToSemanticConsolidator;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EpisodicToSemanticConsolidator 测试。
 *
 * @author zsg
 * @since 2026-03-27
 */
@DisplayName("EpisodicToSemanticConsolidator 测试")
class EpisodicToSemanticConsolidatorTest {

    @Test
    void 长对话巩固不应再触发新增知识提取() {
        var episodicMemory = mock(EpisodicMemory.class);
        var semanticMemory = mock(SemanticMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var properties = new AgentLearningProperties();
        properties.getConsolidation().setLookbackDays(7);
        properties.getConsolidation().setHighFrequencyThreshold(2);
        properties.getConsolidation().setImportanceBoostStep(0.1f);
        properties.getConsolidation().setImportanceBoostMax(0.3f);

        var consolidator = new EpisodicToSemanticConsolidator(
                episodicMemory,
                semanticMemory,
                jdbcTemplate,
                properties
        );

        var now = Instant.now();
        var conversation = new ConversationRecord(
                "conv-1",
                "session-1",
                "聊聊小说世界观",
                null,
                List.of(new MessageRecord(
                        "msg-1",
                        "conv-1",
                        "assistant",
                        "这是一个很长的对话内容，用于验证巩固阶段不会再尝试新增知识提取。".repeat(20),
                        null,
                        CompressionLevel.ORIGINAL,
                        false,
                        null,
                        512,
                        now
                )),
                now,
                now
        );
        when(episodicMemory.getRecent(1000)).thenReturn(List.of(conversation));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(
                new TemporalEntity(
                        "entity-1",
                        EntityType.TOPIC,
                        "世界观",
                        "已有实体",
                        Map.of(),
                        1,
                        true,
                        now,
                        null,
                        null,
                        0.8f,
                        0.5f,
                        0,
                        null,
                        now,
                        now
                ,
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
                        now)
        ));
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        var stats = consolidator.consolidate();

        assertThat(stats.extractionsTriggered()).isZero();
        verify(semanticMemory, never()).upsertWithConflictDetection(
                any(TemporalEntity.class), anyString(), any(MemoryWriteContext.class));
    }

    @Test
    void 上次巩固时间污染时应直接失败() {
        var episodicMemory = mock(EpisodicMemory.class);
        var semanticMemory = mock(SemanticMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var properties = new AgentLearningProperties();
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of("bad-timestamp"));

        var consolidator = new EpisodicToSemanticConsolidator(
                episodicMemory,
                semanticMemory,
                jdbcTemplate,
                properties
        );

        assertThatThrownBy(consolidator::consolidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语义巩固上次巩固时间解析失败: bad-timestamp");
    }

    @Test
    void 高频实体重要度更新命中零行时应直接失败() {
        var episodicMemory = mock(EpisodicMemory.class);
        var semanticMemory = mock(SemanticMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var properties = new AgentLearningProperties();
        properties.getConsolidation().setHighFrequencyThreshold(1);

        var consolidator = new EpisodicToSemanticConsolidator(
                episodicMemory,
                semanticMemory,
                jdbcTemplate,
                properties
        );

        var now = Instant.now();
        when(episodicMemory.getRecent(1000)).thenReturn(List.of(new ConversationRecord(
                "conv-1",
                "session-1",
                "测试",
                null,
                List.of(new MessageRecord(
                        "msg-1",
                        "conv-1",
                        "user",
                        "世界观",
                        null,
                        CompressionLevel.ORIGINAL,
                        false,
                        null,
                        10,
                        now)),
                now,
                now
        )));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(new TemporalEntity(
                "entity-1",
                EntityType.TOPIC,
                "世界观",
                "已有实体",
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                0.8f,
                0.5f,
                0,
                null,
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
                now)));
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(consolidator::consolidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语义巩固: 实体重要度更新失败");
    }
}
