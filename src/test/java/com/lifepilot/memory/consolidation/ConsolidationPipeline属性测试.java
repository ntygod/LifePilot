package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.consolidation.ConsolidationPipeline;
import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.config.MemoryProperties;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsolidationPipelinePropertiesTest {

    @Property(tries = 100)
    @SuppressWarnings("unchecked")
    void cronModeNeverTriggersIdleConsolidation(
            @ForAll("idleThresholds") int idleThreshold,
            @ForAll("cooldowns") int cooldown) {

        var properties = new MemoryProperties();
        properties.getConsolidation().setTriggerMode("CRON");
        properties.getConsolidation().setIdleThresholdMinutes(idleThreshold);
        properties.getConsolidation().setIdleCooldownMinutes(cooldown);

        var pipeline = mock(ConsolidationPipeline.class);
        var pipelineProvider = (ObjectProvider<ConsolidationPipeline>) mock(ObjectProvider.class);
        when(pipelineProvider.getIfAvailable()).thenReturn(pipeline);

        var jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(Instant.EPOCH.toString());

        var config = new MemoryAutoConfiguration(properties, jdbcTemplate, pipelineProvider);
        config.checkIdleConsolidation();

        verify(pipeline, never()).consolidate();
    }

    @Provide
    Arbitrary<Integer> idleThresholds() {
        return Arbitraries.integers().between(1, 120);
    }

    @Provide
    Arbitrary<Integer> cooldowns() {
        return Arbitraries.integers().between(1, 180);
    }
}
