package com.lifepilot.eval.evaluator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DimensionScore record 单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
class DimensionScoreTest {

    @Test
    void 集合字段防御性拷贝() {
        var violations = new ArrayList<>(List.of("v1"));
        var suggestions = new ArrayList<>(List.of("s1"));

        var score = new DimensionScore("toolSelection", 0.9, violations, suggestions);

        // 修改原始集合不影响 record 内部
        violations.add("v2");
        suggestions.add("s2");

        assertEquals(1, score.violations().size());
        assertEquals(1, score.suggestions().size());
    }

    @Test
    void null集合字段默认为空() {
        var score = new DimensionScore("toolSelection", 0.9, null, null);

        assertNotNull(score.violations());
        assertTrue(score.violations().isEmpty());
        assertNotNull(score.suggestions());
        assertTrue(score.suggestions().isEmpty());
    }

    @Test
    void 集合字段不可变() {
        var score = new DimensionScore("toolSelection", 0.9, List.of("v1"), List.of("s1"));

        assertThrows(UnsupportedOperationException.class, () -> score.violations().add("v2"));
        assertThrows(UnsupportedOperationException.class, () -> score.suggestions().add("s2"));
    }

    @Test
    void 字段值正确存储() {
        var score = new DimensionScore("stepEfficiency", 0.75, List.of("慢"), List.of("优化"));

        assertEquals("stepEfficiency", score.dimensionName());
        assertEquals(0.75, score.score());
        assertEquals(List.of("慢"), score.violations());
        assertEquals(List.of("优化"), score.suggestions());
    }
}
