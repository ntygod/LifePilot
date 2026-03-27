package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        var properties = new MemoryProperties();
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
                )
        ));

        var stats = consolidator.consolidate();

        assertThat(stats.extractionsTriggered()).isZero();
        verify(semanticMemory, never()).upsertWithConflictDetection(any(TemporalEntity.class), anyString());
    }
}
