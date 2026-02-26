package com.lifepilot.eval.scenario;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScenarioSerializer 单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
class ScenarioSerializerTest {

    private ScenarioSerializer serializer;
    private ObjectMapper yamlReader;

    @BeforeEach
    void setUp() {
        serializer = new ScenarioSerializer();
        yamlReader = new ObjectMapper(new YAMLFactory());
        yamlReader.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void serialize_完整场景_生成有效yaml() throws Exception {
        var scenario = BenchmarkScenario.builder()
                .id("s1")
                .name("测试场景")
                .userInput("你好")
                .expectedToolCalls(List.of("tool1", "tool2"))
                .expectedOutputPattern(".*你好.*")
                .dimensionWeights(Map.of("toolSelection", 0.5, "stepEfficiency", 0.5))
                .timeoutSeconds(30)
                .mockToolResponses(Map.of("tool1", "{\"result\": \"ok\"}"))
                .initialContext(Map.of("key", "value"))
                .tags(List.of("core", "smoke"))
                .llmJudgeCriteria("回答应包含问候语")
                .expectedTokenBudget(500)
                .expectedStepCount(3)
                .build();

        var yaml = serializer.serialize(scenario);

        assertNotNull(yaml);
        assertFalse(yaml.startsWith("---"), "不应包含文档起始标记");

        // 反序列化验证往返一致性
        var parsed = yamlReader.readValue(yaml, BenchmarkScenario.class);
        assertEquals(scenario.id(), parsed.id());
        assertEquals(scenario.name(), parsed.name());
        assertEquals(scenario.userInput(), parsed.userInput());
        assertEquals(scenario.expectedToolCalls(), parsed.expectedToolCalls());
        assertEquals(scenario.expectedOutputPattern(), parsed.expectedOutputPattern());
        assertEquals(scenario.dimensionWeights(), parsed.dimensionWeights());
        assertEquals(scenario.timeoutSeconds(), parsed.timeoutSeconds());
        assertEquals(scenario.tags(), parsed.tags());
        assertEquals(scenario.llmJudgeCriteria(), parsed.llmJudgeCriteria());
        assertEquals(scenario.expectedTokenBudget(), parsed.expectedTokenBudget());
        assertEquals(scenario.expectedStepCount(), parsed.expectedStepCount());
    }

    @Test
    void serialize_null可选字段_不输出到yaml() {
        var scenario = BenchmarkScenario.builder()
                .id("s2")
                .name("最小场景")
                .userInput("测试")
                .timeoutSeconds(10)
                .expectedTokenBudget(100)
                .expectedStepCount(1)
                .build();

        var yaml = serializer.serialize(scenario);

        assertFalse(yaml.contains("mockToolResponses"), "null 字段不应出现在 YAML 中");
        assertFalse(yaml.contains("initialContext"), "null 字段不应出现在 YAML 中");
        assertFalse(yaml.contains("llmJudgeCriteria"), "null 字段不应出现在 YAML 中");
        assertFalse(yaml.contains("expectedOutputPattern"), "null 字段不应出现在 YAML 中");
    }

    @Test
    void serialize_空集合字段_正常输出() throws Exception {
        var scenario = BenchmarkScenario.builder()
                .id("s3")
                .name("空集合场景")
                .userInput("测试")
                .expectedToolCalls(List.of())
                .dimensionWeights(Map.of())
                .tags(List.of())
                .timeoutSeconds(10)
                .expectedTokenBudget(100)
                .expectedStepCount(1)
                .build();

        var yaml = serializer.serialize(scenario);
        var parsed = yamlReader.readValue(yaml, BenchmarkScenario.class);

        assertEquals("s3", parsed.id());
        assertTrue(parsed.expectedToolCalls().isEmpty());
        assertTrue(parsed.dimensionWeights().isEmpty());
        assertTrue(parsed.tags().isEmpty());
    }

    @Test
    void serialize_往返一致性_反序列化后字段相等() throws Exception {
        var original = BenchmarkScenario.builder()
                .id("roundtrip-001")
                .name("往返测试")
                .userInput("帮我查天气")
                .expectedToolCalls(List.of("weather.query"))
                .dimensionWeights(Map.of("toolSelection", 0.6, "tokenEfficiency", 0.4))
                .timeoutSeconds(60)
                .tags(List.of("core"))
                .expectedTokenBudget(1000)
                .expectedStepCount(3)
                .build();

        var yaml = serializer.serialize(original);
        var restored = yamlReader.readValue(yaml, BenchmarkScenario.class);

        assertEquals(original, restored);
    }
}
