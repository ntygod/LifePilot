package com.lifepilot.eval.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EvalResult record 单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
class EvalResultTest {

    @Test
    void 集合字段防御性拷贝() {
        var scores = new java.util.HashMap<String, Double>();
        scores.put("toolSelection", 0.9);
        var violations = new java.util.ArrayList<>(List.of("v1"));
        var suggestions = new java.util.ArrayList<>(List.of("s1"));

        var result = EvalResult.builder()
                .evalId("e1")
                .traceId("t1")
                .scenarioId("s1")
                .dimensionScores(scores)
                .overallScore(0.8)
                .violations(violations)
                .suggestions(suggestions)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId("run1")
                .build();

        // 修改原始集合不影响 record 内部
        scores.put("extra", 1.0);
        violations.add("v2");
        suggestions.add("s2");

        assertEquals(1, result.dimensionScores().size());
        assertEquals(1, result.violations().size());
        assertEquals(1, result.suggestions().size());
    }

    @Test
    void null集合字段默认为空() {
        var result = EvalResult.builder()
                .evalId("e1")
                .traceId("t1")
                .scenarioId("s1")
                .dimensionScores(null)
                .overallScore(0.5)
                .violations(null)
                .suggestions(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId("run1")
                .build();

        assertNotNull(result.dimensionScores());
        assertTrue(result.dimensionScores().isEmpty());
        assertNotNull(result.violations());
        assertTrue(result.violations().isEmpty());
        assertNotNull(result.suggestions());
        assertTrue(result.suggestions().isEmpty());
    }

    @Test
    void passed_评分大于等于阈值返回true() {
        var result = buildResult(0.8);
        assertTrue(result.passed(0.7));
        assertTrue(result.passed(0.8));
    }

    @Test
    void passed_评分小于阈值返回false() {
        var result = buildResult(0.6);
        assertFalse(result.passed(0.7));
    }

    @Test
    void passed_边界值精确等于阈值() {
        var result = buildResult(0.7);
        assertTrue(result.passed(0.7));
    }

    @Test
    void toBuilder_创建修改副本() {
        var original = buildResult(0.8);
        var modified = original.toBuilder().overallScore(0.5).build();

        assertEquals(0.8, original.overallScore());
        assertEquals(0.5, modified.overallScore());
        assertEquals(original.evalId(), modified.evalId());
    }

    @Test
    void nullable字段可为null() {
        var result = EvalResult.builder()
                .evalId("e1")
                .traceId("t1")
                .scenarioId("s1")
                .overallScore(0.5)
                .llmJudgeScore(null)
                .llmJudgeJustification(null)
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .gitCommitHash(null)
                .gitBranch(null)
                .evalRunId("run1")
                .build();

        assertNull(result.llmJudgeScore());
        assertNull(result.llmJudgeJustification());
        assertNull(result.gitCommitHash());
        assertNull(result.gitBranch());
    }

    private EvalResult buildResult(double overallScore) {
        return EvalResult.builder()
                .evalId("e1")
                .traceId("t1")
                .scenarioId("s1")
                .dimensionScores(Map.of("toolSelection", 0.9))
                .overallScore(overallScore)
                .violations(List.of())
                .suggestions(List.of())
                .llmJudgeTokensUsed(0)
                .evaluatedAt(Instant.now())
                .evalRunId("run1")
                .build();
    }
}
