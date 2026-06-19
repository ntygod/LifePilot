package com.lifepilot.memory.forgetting;

import com.lifepilot.agent.learning.forgetting.ForgettingEngine;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ForgettingEngine 访问保护测试 — 验证高频访问保护和近期访问保护机制，
 * 确保学习引擎正在利用的高价值记忆不会被遗忘引擎误删。
 *
 * @author zsg
 * @since 2026-04-16
 */
@ExtendWith(MockitoExtension.class)
class ForgettingEngine_访问保护测试 {

    @Mock
    private SemanticMemory semanticMemory;
    @Mock
    private GenerationRouter generationRouter;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PromptRegistry promptRegistry;

    private AgentLearningProperties properties;
    private AgentLearningProperties.Forgetting forgettingConfig;

    private ForgettingEngine engine;

    @BeforeEach
    void 初始化() {
        properties = new AgentLearningProperties();
        forgettingConfig = new AgentLearningProperties.Forgetting();
        // 设置合理的默认值用于测试
        forgettingConfig.setMaxRetentionDays(365);
        forgettingConfig.setLruThresholdDays(90);
        forgettingConfig.setPriorityDecayRate(0.02f);
        forgettingConfig.setPriorityDecayThreshold(0.2f);
        forgettingConfig.setReflectionSummaryMinImportance(0.3f);
        forgettingConfig.setReflectionSummaryMaxImportance(0.8f);
        forgettingConfig.setMaxForgetPerRun(100);
        forgettingConfig.setProtectionThreshold(0.9f);
        forgettingConfig.setProtectedTypes(Set.of("PREFERENCE", "HABIT", "GOAL"));
        // 访问保护配置
        forgettingConfig.setHighAccessCountProtection(10);
        forgettingConfig.setRecentAccessProtectionDays(7);
        properties.setForgetting(forgettingConfig);

        engine = new ForgettingEngine(
                semanticMemory, generationRouter, jdbcTemplate, properties, promptRegistry);
    }

    @Test
    void 高频访问实体受保护不被遗忘() {
        // accessCount=15 >= highAccessCountProtection(10)，应受保护
        var highAccessEntity = 创建实体("high-access-1", EntityType.TOPIC, 0.1f, 15,
                Instant.now().minus(100, ChronoUnit.DAYS),
                Instant.now().minus(200, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(highAccessEntity));

        int count = engine.forget();

        assertEquals(0, count);
        verify(semanticMemory, never()).archive(any(), any(ChangeSource.class));
    }

    @Test
    void 近期访问实体受保护不被遗忘() {
        // 3 天前访问，在 7 天保护窗口内，应受保护
        var recentAccessEntity = 创建实体("recent-access-1", EntityType.TOPIC, 0.1f, 1,
                Instant.now().minus(3, ChronoUnit.DAYS),
                Instant.now().minus(200, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(recentAccessEntity));

        int count = engine.forget();

        assertEquals(0, count);
        verify(semanticMemory, never()).archive(any(), any(ChangeSource.class));
    }

    @Test
    void 超期低频实体可被遗忘() {
        // 100 天前访问（超出 7 天保护窗口），accessCount=1（< 阈值 10），importanceScore=0.1（< 0.9），
        // 类型为 TOPIC（不在 protectedTypes 中）—— 不满足任何保护条件，应可被遗忘
        var forgettableEntity = 创建实体("forgettable-1", EntityType.TOPIC, 0.1f, 1,
                Instant.now().minus(100, ChronoUnit.DAYS),
                Instant.now().minus(400, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(forgettableEntity));

        int count = engine.forget();

        // 实体应该进入候选列表并被遗忘（archive 或 compress）
        assertTrue(count > 0, "超期低频实体应可被遗忘");
    }

    // ==================== 辅助方法 ====================

    private static TemporalEntity 创建实体(String id, EntityType type,
                                           float importanceScore, int accessCount,
                                           Instant lastAccessedAt, Instant createdAt) {
        return new TemporalEntity(
                id, type, "测试实体-" + id, "测试描述",
                Map.of(), 1, true, createdAt, null,
                null, 0.8f, importanceScore, accessCount, lastAccessedAt,
                createdAt, createdAt
        );
    }
}
