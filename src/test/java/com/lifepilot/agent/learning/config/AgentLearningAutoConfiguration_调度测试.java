package com.lifepilot.agent.learning.config;

import com.lifepilot.agent.learning.consolidation.ConsolidationScheduler;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 学习调度单元测试。
 *
 * @author zsg
 * @since 2026-07-03
 */
class AgentLearningAutoConfiguration_调度测试 {

    @Test
    void 空闲REM失败后应进入冷却避免重复触发() throws Exception {
        var properties = new AgentLearningProperties();
        properties.getConsolidation().setIdleThresholdMinutes(1);
        properties.getConsolidation().setIdleCooldownMinutes(60);
        var jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenReturn(Instant.now().minus(Duration.ofHours(2)).toString());
        var scheduler = mock(ConsolidationScheduler.class);
        doThrow(new IllegalStateException("template missing")).when(scheduler).runIdleStages();
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsolidationScheduler> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(scheduler);
        var config = new AgentLearningAutoConfiguration(properties, jdbcTemplate, provider);
        setLastIdleConsolidationTime(config, Instant.now().minus(Duration.ofHours(2)));

        config.pollConsolidationTriggers();
        config.pollConsolidationTriggers();

        verify(scheduler, times(2)).checkProfileDebounce();
        verify(scheduler, times(1)).runIdleStages();
    }

    @Test
    void 完成事件关闭记忆学习时不触发巩固调度() {
        var properties = new AgentLearningProperties();
        var jdbcTemplate = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConsolidationScheduler> provider = mock(ObjectProvider.class);
        var config = new AgentLearningAutoConfiguration(properties, jdbcTemplate, provider);

        config.onConversationCompleted(new ConversationCompletedEvent(
                this,
                "user-1",
                "session-1",
                "turn-1",
                "用户要求不要写入长期记忆",
                false,
                "user_memory_write_denied"));

        verify(provider, never()).getObject();
    }

    private static void setLastIdleConsolidationTime(AgentLearningAutoConfiguration config,
                                                     Instant value) throws Exception {
        Field field = AgentLearningAutoConfiguration.class.getDeclaredField("lastIdleConsolidationTime");
        field.setAccessible(true);
        field.set(config, value);
    }
}
