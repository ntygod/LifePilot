package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@DisplayName("MemoryAutoConfiguration idle consolidation integration tests")
class ConsolidationPipelineIdleTriggerIntegrationTest {

    private MemoryProperties properties;
    private EpisodicToSemanticConsolidator semanticConsolidator;
    private EpisodicToProceduralConsolidator proceduralConsolidator;
    private ConsolidationPipeline pipeline;
    private ObjectProvider<ConsolidationPipeline> pipelineProvider;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        semanticConsolidator = mock(EpisodicToSemanticConsolidator.class);
        proceduralConsolidator = mock(EpisodicToProceduralConsolidator.class);

        when(semanticConsolidator.consolidate()).thenReturn(
                new ConsolidationStats("SEMANTIC", 0, 0, 0, 0, 0, 0, 0L));
        when(proceduralConsolidator.consolidate()).thenReturn(
                new ConsolidationStats("PROCEDURAL", 0, 0, 0, 0, 0, 0, 0L));

        pipeline = new ConsolidationPipeline(
                semanticConsolidator, proceduralConsolidator, properties, null, null, null, null, null, null, null);
        pipelineProvider = mock(ObjectProvider.class);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);

        jdbcTemplate = mock(JdbcTemplate.class);
    }

    @Test
    void idleModeTriggersConsolidationWhenConversationIsIdle() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(5);
        properties.getConsolidation().setIdleCooldownMinutes(60);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.EPOCH.toString());

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, pipelineProvider);
        config.checkIdleConsolidation();

        verify(semanticConsolidator).consolidate();
        verify(proceduralConsolidator).consolidate();
    }

    @Test
    void idleModeDoesNotTriggerAgainDuringCooldown() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(1);
        properties.getConsolidation().setIdleCooldownMinutes(60);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.EPOCH.toString());

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, pipelineProvider);
        config.checkIdleConsolidation();
        config.checkIdleConsolidation();

        verify(semanticConsolidator, times(1)).consolidate();
        verify(proceduralConsolidator, times(1)).consolidate();
    }

    @Test
    void cronModeSkipsIdleConsolidation() {
        properties.getConsolidation().setTriggerMode("CRON");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.EPOCH.toString());

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, pipelineProvider);
        config.checkIdleConsolidation();

        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }

    @Test
    void hybridModeSupportsIdleAndScheduledConsolidation() {
        properties.getConsolidation().setTriggerMode("HYBRID");
        properties.getConsolidation().setIdleThresholdMinutes(5);
        properties.getConsolidation().setIdleCooldownMinutes(60);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.EPOCH.toString());

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, pipelineProvider);
        config.checkIdleConsolidation();
        pipeline.scheduledConsolidate();

        verify(semanticConsolidator, times(2)).consolidate();
        verify(proceduralConsolidator, times(2)).consolidate();
    }

    @Test
    void idleCheckSkipsWhenThresholdIsNotReached() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(30);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.now().toString());

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, pipelineProvider);
        config.checkIdleConsolidation();

        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }

    @Test
    void idleCheckSkipsWhenPipelineBeanIsMissing() {
        properties.getConsolidation().setTriggerMode("IDLE");
        properties.getConsolidation().setIdleThresholdMinutes(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.EPOCH.toString());

        var emptyPipelineProvider = (ObjectProvider<ConsolidationPipeline>) mock(ObjectProvider.class);
        when(emptyPipelineProvider.getIfAvailable()).thenReturn(null);

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, emptyPipelineProvider);
        config.checkIdleConsolidation();

        verify(semanticConsolidator, never()).consolidate();
        verify(proceduralConsolidator, never()).consolidate();
    }
}
