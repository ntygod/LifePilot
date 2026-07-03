package com.lifepilot.memory.consumption;

import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.TemporalEntity;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExperienceRanking 属性测试 —— 验证统一排序口径的单调性与确定性。
 *
 * @author zsg
 * @since 2026-06-12
 */
class ExperienceRanking属性测试 {

    private static final Instant NOW = Instant.parse("2026-06-12T00:00:00Z");

    private TemporalEntity entity(float trust, float importance, Instant updatedAt) {
        TemporalEntity base = new TemporalEntity(
                "e1", EntityType.EXPERIENCE, "exp", "desc", Map.of(), 1, true,
                NOW.minus(Duration.ofDays(60)), null, "conv",
                1.0f, importance, 0, null, NOW.minus(Duration.ofDays(60)), updatedAt,
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
                        updatedAt);
        return base.withQuality(
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.UNKNOWN,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.VERIFIED,
                trust, 1, null);
    }

    @Test
    void null实体返回0() {
        assertEquals(0.0, ExperienceRanking.score(null, NOW));
    }

    @Property(tries = 200)
    void 分值落在0到1之间(@ForAll @FloatRange(min = 0f, max = 1f) float trust,
                     @ForAll @FloatRange(min = 0f, max = 1f) float importance) {
        double s = ExperienceRanking.score(entity(trust, importance, NOW), NOW);
        assertTrue(s >= 0.0 && s <= 1.0, "分值应在 [0,1]: " + s);
    }

    @Property(tries = 200)
    void 可信度更高者分值不更低(@ForAll @FloatRange(min = 0f, max = 0.5f) float lowTrust,
                          @ForAll @FloatRange(min = 0.5f, max = 1f) float highTrust) {
        var low = ExperienceRanking.score(entity(lowTrust, 0.5f, NOW), NOW);
        var high = ExperienceRanking.score(entity(highTrust, 0.5f, NOW), NOW);
        assertTrue(high >= low, "可信度更高分值应不更低");
    }

    @Test
    void 越新的实体新近度越高() {
        var fresh = ExperienceRanking.score(entity(0.5f, 0.5f, NOW), NOW);
        var old = ExperienceRanking.score(entity(0.5f, 0.5f, NOW.minus(Duration.ofDays(40))), NOW);
        assertTrue(fresh > old, "更新近的实体分值应更高");
    }

    @Test
    void 确定性_同输入同输出() {
        var e = entity(0.7f, 0.6f, NOW.minus(Duration.ofDays(5)));
        assertEquals(ExperienceRanking.score(e, NOW), ExperienceRanking.score(e, NOW));
    }
}
