package com.lifepilot.eval.scenario;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BenchmarkScenario record 单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
class BenchmarkScenarioTest {

    @Test
    void 必填字段id为null_抛出NullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkScenario(null, "name", "input", List.of(), null, Map.of(), 60, null, null, null, List.of(), null, 1000, 3, null, null, null));
    }

    @Test
    void 必填字段name为null_抛出NullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkScenario("id", null, "input", List.of(), null, Map.of(), 60, null, null, null, List.of(), null, 1000, 3, null, null, null));
    }

    @Test
    void 必填字段userInput为null_抛出NullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkScenario("id", "name", null, List.of(), null, Map.of(), 60, null, null, null, List.of(), null, 1000, 3, null, null, null));
    }

    @Test
    void 可选字段为null_构造成功() {
        var scenario = new BenchmarkScenario("id", "name", "input", null, null, null, 60, null, null, null, null, null, 1000, 3, null, null, null);

        assertEquals("id", scenario.id());
        assertEquals("name", scenario.name());
        assertEquals("input", scenario.userInput());
        assertNull(scenario.expectedOutputPattern());
        assertNull(scenario.mockToolResponses());
        assertNull(scenario.mockTools());
        assertNull(scenario.initialContext());
        assertNull(scenario.llmJudgeCriteria());
        assertNull(scenario.category());
        assertNull(scenario.difficulty());
        assertNull(scenario.description());
    }

    @Test
    void 集合字段为null时_使用空集合默认值() {
        var scenario = new BenchmarkScenario("id", "name", "input", null, null, null, 60, null, null, null, null, null, 1000, 3, null, null, null);

        assertEquals(List.of(), scenario.expectedToolCalls());
        assertEquals(Map.of(), scenario.dimensionWeights());
        assertEquals(List.of(), scenario.tags());
    }

    @Test
    void 集合字段防御性拷贝_修改原集合不影响record() {
        var tools = new java.util.ArrayList<>(List.of("tool1", "tool2"));
        var weights = new java.util.HashMap<>(Map.of("dim1", 0.5, "dim2", 0.5));
        var tags = new java.util.ArrayList<>(List.of("tag1"));
        var mocks = new java.util.HashMap<>(Map.of("tool1", "response1"));
        var ctx = new java.util.HashMap<>(Map.of("key", "value"));

        var scenario = new BenchmarkScenario("id", "name", "input", tools, null, weights, 60, mocks, null, ctx, tags, null, 1000, 3, null, null, null);

        // 修改原集合
        tools.add("tool3");
        weights.put("dim3", 0.0);
        tags.add("tag2");
        mocks.put("tool2", "response2");
        ctx.put("key2", "value2");

        // record 内部不受影响
        assertEquals(2, scenario.expectedToolCalls().size());
        assertEquals(2, scenario.dimensionWeights().size());
        assertEquals(1, scenario.tags().size());
        assertEquals(1, scenario.mockToolResponses().size());
        assertEquals(1, scenario.initialContext().size());
    }

    @Test
    void 集合字段不可变_修改抛出异常() {
        var scenario = new BenchmarkScenario("id", "name", "input", List.of("tool1"), null,
                Map.of("dim1", 1.0), 60, Map.of("t", "r"), null, Map.of("k", "v"), List.of("tag"), null, 1000, 3, null, null, null);

        assertThrows(UnsupportedOperationException.class, () -> scenario.expectedToolCalls().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> scenario.dimensionWeights().put("x", 0.0));
        assertThrows(UnsupportedOperationException.class, () -> scenario.tags().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> scenario.mockToolResponses().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> scenario.initialContext().put("x", "y"));
    }

    @Test
    void builder模式构造_正常工作() {
        var scenario = BenchmarkScenario.builder()
                .id("test-001")
                .name("测试场景")
                .userInput("你好")
                .expectedToolCalls(List.of("tool1"))
                .dimensionWeights(Map.of("toolSelection", 1.0))
                .timeoutSeconds(30)
                .tags(List.of("core"))
                .expectedTokenBudget(500)
                .expectedStepCount(2)
                .category("basic")
                .difficulty("easy")
                .description("测试描述")
                .build();

        assertEquals("test-001", scenario.id());
        assertEquals("测试场景", scenario.name());
        assertEquals(30, scenario.timeoutSeconds());
        assertEquals("basic", scenario.category());
        assertEquals("easy", scenario.difficulty());
        assertEquals("测试描述", scenario.description());
    }
}
