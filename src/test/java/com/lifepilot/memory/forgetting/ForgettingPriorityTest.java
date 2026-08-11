package com.lifepilot.memory.forgetting;

import com.lifepilot.agent.learning.forgetting.ForgettingPriority;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ForgettingPriority 遗忘优先级计算器单元测试。
 *
 * @author zsg
 * @since 2026-03-01
 */
class ForgettingPriorityTest {

    private ForgettingPriority calculator;
    private AgentLearningProperties.Forgetting config;

    @BeforeEach
    void setUp() {
        config = new AgentLearningProperties.Forgetting();
        // 使用默认值：maxRetentionDays=365, privacyAwareBoost=0.3
        calculator = new ForgettingPriority(config);
    }

    @Test
    void 综合评分公式_各因子正确计算() {
        // 100 天前创建，50 天前最后访问，accessCount=4，importanceScore=0.6，无 PII
        var now = Instant.now();
        var entity = createEntity(
                0.6f, 4, now.minus(50, ChronoUnit.DAYS),
                now.minus(100, ChronoUnit.DAYS), Map.of()
        );

        float priority = calculator.calculate(entity);

        // timeFactor = 50 / 365 ≈ 0.1370
        float timeFactor = 50.0f / 365;
        // accessFactor = 1.0 / (1 + 4) = 0.2
        float accessFactor = 1.0f / 5;
        // importanceFactor = 1.0 - 0.6 = 0.4
        float importanceFactor = 0.4f;
        // privacyFactor = 1.0（无 PII）
        float expected = (timeFactor * 0.3f + accessFactor * 0.3f + importanceFactor * 0.2f) * 1.0f;

        assertEquals(expected, priority, 0.001f);
    }

    @Test
    void PII实体_优先级乘以隐私因子() {
        var now = Instant.now();
        var entityWithPii = createEntity(
                0.5f, 2, now.minus(30, ChronoUnit.DAYS),
                now.minus(60, ChronoUnit.DAYS), Map.of("pii", "true")
        );
        var entityWithoutPii = createEntity(
                0.5f, 2, now.minus(30, ChronoUnit.DAYS),
                now.minus(60, ChronoUnit.DAYS), Map.of()
        );

        float piiPriority = calculator.calculate(entityWithPii);
        float normalPriority = calculator.calculate(entityWithoutPii);

        // PII 实体优先级应更高
        assertTrue(piiPriority > normalPriority);
        // PII 因子 = 1.0 + 0.3 = 1.3
        assertEquals(piiPriority / normalPriority, 1.3f, 0.01f);
    }

    @Test
    void lastAccessedAt为null时_使用createdAt计算timeFactor() {
        var now = Instant.now();
        var entity = createEntity(
                0.5f, 0, null,
                now.minus(100, ChronoUnit.DAYS), Map.of()
        );

        float priority = calculator.calculate(entity);

        // timeFactor 应基于 createdAt（100 天前）
        float timeFactor = Math.min(1.0f, 100.0f / 365);
        float accessFactor = 1.0f; // 1.0 / (1 + 0)
        float importanceFactor = 0.5f; // 1.0 - 0.5
        float expected = (timeFactor * 0.3f + accessFactor * 0.3f + importanceFactor * 0.2f) * 1.0f;

        assertEquals(expected, priority, 0.001f);
    }

    @Test
    void timeFactor上限为1() {
        var now = Instant.now();
        // 超过 maxRetentionDays（365 天）的实体
        var entity = createEntity(
                0.5f, 0, now.minus(500, ChronoUnit.DAYS),
                now.minus(600, ChronoUnit.DAYS), Map.of()
        );

        float priority = calculator.calculate(entity);

        // timeFactor 应被 clamp 到 1.0
        float accessFactor = 1.0f;
        float importanceFactor = 0.5f;
        float expected = (1.0f * 0.3f + accessFactor * 0.3f + importanceFactor * 0.2f) * 1.0f;

        assertEquals(expected, priority, 0.001f);
    }

    @Test
    void 高重要度低访问量_优先级较低() {
        var now = Instant.now();
        // 高重要度（0.9），最近访问（1 天前），高访问量（100 次）
        var importantEntity = createEntity(
                0.9f, 100, now.minus(1, ChronoUnit.DAYS),
                now.minus(30, ChronoUnit.DAYS), Map.of()
        );
        // 低重要度（0.1），很久未访问（200 天前），零访问
        var unimportantEntity = createEntity(
                0.1f, 0, now.minus(200, ChronoUnit.DAYS),
                now.minus(300, ChronoUnit.DAYS), Map.of()
        );

        float importantPriority = calculator.calculate(importantEntity);
        float unimportantPriority = calculator.calculate(unimportantEntity);

        // 不重要的实体遗忘优先级应更高
        assertTrue(unimportantPriority > importantPriority);
    }

    private TemporalEntity createEntity(float importanceScore, int accessCount,
                                        Instant lastAccessedAt, Instant createdAt,
                                        Map<String, Object> properties) {
        return new TemporalEntity(
                "test-id", EntityType.TOPIC, "测试实体", "测试描述",
                properties, 1, true, createdAt, null,
                null, 0.8f, importanceScore, accessCount, lastAccessedAt,
                createdAt, createdAt
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
                createdAt);
    }
}
