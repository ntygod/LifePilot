package com.lifepilot.eval.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lifepilot.eval.config.EvalConfigProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScenarioLoader 单元测试。
 *
 * @author zsg
 * @since 2026-08-01
 */
class ScenarioLoaderTest {

    @TempDir
    Path tempDir;

    private EvalConfigProperties config;
    private ScenarioLoader loader;

    @BeforeEach
    void setUp() {
        config = new EvalConfigProperties();
        config.setScenarioDirectory(tempDir.toString());
        loader = new ScenarioLoader(config);
    }

    // --- loadAll ---

    @Test
    void loadAll_有效yaml文件_解析成功() throws IOException {
        writeYaml("scenario1.yml", """
                id: "s1"
                name: "场景1"
                userInput: "你好"
                expectedToolCalls:
                  - "tool1"
                dimensionWeights:
                  toolSelection: 0.5
                  stepEfficiency: 0.5
                timeoutSeconds: 30
                expectedTokenBudget: 500
                expectedStepCount: 2
                tags:
                  - "core"
                """);

        var scenarios = loader.loadAll();

        assertEquals(1, scenarios.size());
        var s = scenarios.getFirst();
        assertEquals("s1", s.id());
        assertEquals("场景1", s.name());
        assertEquals("你好", s.userInput());
        assertEquals(List.of("tool1"), s.expectedToolCalls());
        assertEquals(0.5, s.dimensionWeights().get("toolSelection"));
        assertEquals(30, s.timeoutSeconds());
        assertEquals(List.of("core"), s.tags());
    }

    @Test
    void loadAll_多个yaml文件_全部加载() throws IOException {
        writeYaml("a.yml", validYaml("s1", "场景1", 0.6, 0.4));
        writeYaml("b.yaml", validYaml("s2", "场景2", 0.7, 0.3));

        var scenarios = loader.loadAll();
        assertEquals(2, scenarios.size());
    }

    @Test
    void loadAll_空文件_跳过并不报错() throws IOException {
        writeYaml("empty.yml", "");
        writeYaml("valid.yml", validYaml("s1", "场景1", 0.5, 0.5));

        var scenarios = loader.loadAll();
        assertEquals(1, scenarios.size());
        assertEquals("s1", scenarios.getFirst().id());
    }

    @Test
    void loadAll_仅空白文件_跳过() throws IOException {
        writeYaml("blank.yml", "   \n  \n  ");

        var scenarios = loader.loadAll();
        assertTrue(scenarios.isEmpty());
    }

    @Test
    void loadAll_目录不存在_抛出ScenarioLoadException() {
        config.setScenarioDirectory(tempDir.resolve("nonexistent").toString());
        loader = new ScenarioLoader(config);

        var ex = assertThrows(ScenarioLoadException.class, loader::loadAll);
        assertTrue(ex.getMessage().contains("场景目录不存在"));
    }

    @Test
    void loadAll_路径是文件而非目录_抛出ScenarioLoadException() throws IOException {
        var file = tempDir.resolve("not-a-dir.yml");
        Files.writeString(file, "id: x");
        config.setScenarioDirectory(file.toString());
        loader = new ScenarioLoader(config);

        var ex = assertThrows(ScenarioLoadException.class, loader::loadAll);
        assertTrue(ex.getMessage().contains("场景路径不是目录"));
    }

    @Test
    void loadAll_yaml语法错误_抛出ScenarioLoadException() throws IOException {
        writeYaml("bad.yml", "id: [invalid yaml\nname: broken");

        var ex = assertThrows(ScenarioLoadException.class, loader::loadAll);
        assertTrue(ex.getMessage().contains("YAML 解析失败"));
        assertTrue(ex.getMessage().contains("bad.yml"));
    }

    @Test
    void loadAll_非yml文件_被忽略() throws IOException {
        writeYaml("valid.yml", validYaml("s1", "场景1", 0.5, 0.5));
        Files.writeString(tempDir.resolve("readme.txt"), "not a scenario");
        Files.writeString(tempDir.resolve("data.json"), "{}");

        var scenarios = loader.loadAll();
        assertEquals(1, scenarios.size());
    }

    @Test
    void loadAll_返回不可变列表() throws IOException {
        writeYaml("s.yml", validYaml("s1", "场景1", 1.0, 0.0));

        var scenarios = loader.loadAll();
        assertThrows(UnsupportedOperationException.class, () -> scenarios.add(null));
    }

    // --- loadById ---

    @Test
    void loadById_存在的id_返回对应场景() throws IOException {
        writeYaml("s1.yml", validYaml("target-id", "目标场景", 0.5, 0.5));

        var scenario = loader.loadById("target-id");
        assertEquals("target-id", scenario.id());
        assertEquals("目标场景", scenario.name());
    }

    @Test
    void loadById_不存在的id_抛出ScenarioLoadException() throws IOException {
        writeYaml("s1.yml", validYaml("s1", "场景1", 1.0, 0.0));

        var ex = assertThrows(ScenarioLoadException.class, () -> loader.loadById("nonexistent"));
        assertTrue(ex.getMessage().contains("场景不存在"));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    // --- loadByTags ---

    @Test
    void loadByTags_匹配标签_返回对应场景() throws IOException {
        writeYaml("s1.yml", validYamlWithTags("s1", "场景1", List.of("core", "tool")));
        writeYaml("s2.yml", validYamlWithTags("s2", "场景2", List.of("advanced")));

        var results = loader.loadByTags(List.of("core"));
        assertEquals(1, results.size());
        assertEquals("s1", results.getFirst().id());
    }

    @Test
    void loadByTags_多标签匹配_返回所有匹配() throws IOException {
        writeYaml("s1.yml", validYamlWithTags("s1", "场景1", List.of("core")));
        writeYaml("s2.yml", validYamlWithTags("s2", "场景2", List.of("advanced")));

        var results = loader.loadByTags(List.of("core", "advanced"));
        assertEquals(2, results.size());
    }

    @Test
    void loadByTags_无匹配_返回空列表() throws IOException {
        writeYaml("s1.yml", validYamlWithTags("s1", "场景1", List.of("core")));

        var results = loader.loadByTags(List.of("nonexistent"));
        assertTrue(results.isEmpty());
    }

    // --- validateScenarios ---

    @Test
    void validateScenarios_重复id_抛出ScenarioLoadException() {
        var s1 = buildScenario("dup-id", "场景1", Map.of("d1", 1.0));
        var s2 = buildScenario("dup-id", "场景2", Map.of("d1", 1.0));

        var ex = assertThrows(ScenarioLoadException.class, () -> loader.validateScenarios(List.of(s1, s2)));
        assertTrue(ex.getMessage().contains("场景 ID 重复"));
        assertTrue(ex.getMessage().contains("dup-id"));
    }

    @Test
    void validateScenarios_权重和不为1_抛出ScenarioLoadException() {
        var s = buildScenario("s1", "场景1", Map.of("d1", 0.3, "d2", 0.3));

        var ex = assertThrows(ScenarioLoadException.class, () -> loader.validateScenarios(List.of(s)));
        assertTrue(ex.getMessage().contains("维度权重和不为 1.0"));
    }

    @Test
    void validateScenarios_权重和在容差内_通过() {
        // 0.1 + 0.2 + 0.3 + 0.4 = 1.0（浮点精度可能有微小偏差）
        var s = buildScenario("s1", "场景1", Map.of("d1", 0.1, "d2", 0.2, "d3", 0.3, "d4", 0.4));

        assertDoesNotThrow(() -> loader.validateScenarios(List.of(s)));
    }

    @Test
    void validateScenarios_空权重map_跳过校验() {
        var s = buildScenario("s1", "场景1", Map.of());

        assertDoesNotThrow(() -> loader.validateScenarios(List.of(s)));
    }

    // --- 未知字段容忍 ---

    @Test
    void loadAll_未知yaml字段_被忽略() throws IOException {
        writeYaml("s.yml", """
                id: "s1"
                name: "场景1"
                userInput: "你好"
                unknownField: "should be ignored"
                anotherUnknown: 42
                dimensionWeights:
                  d1: 1.0
                timeoutSeconds: 30
                expectedTokenBudget: 500
                expectedStepCount: 2
                """);

        var scenarios = loader.loadAll();
        assertEquals(1, scenarios.size());
        assertEquals("s1", scenarios.getFirst().id());
    }

    // --- 辅助方法 ---

    private void writeYaml(String filename, String content) throws IOException {
        Files.writeString(tempDir.resolve(filename), content);
    }

    private String validYaml(String id, String name, double w1, double w2) {
        return """
                id: "%s"
                name: "%s"
                userInput: "测试输入"
                dimensionWeights:
                  toolSelection: %s
                  stepEfficiency: %s
                timeoutSeconds: 30
                expectedTokenBudget: 500
                expectedStepCount: 2
                tags:
                  - "core"
                """.formatted(id, name, w1, w2);
    }

    private String validYamlWithTags(String id, String name, List<String> tags) {
        var tagLines = new StringBuilder();
        for (var tag : tags) {
            tagLines.append("  - \"").append(tag).append("\"\n");
        }
        return """
                id: "%s"
                name: "%s"
                userInput: "测试输入"
                dimensionWeights:
                  d1: 1.0
                timeoutSeconds: 30
                expectedTokenBudget: 500
                expectedStepCount: 2
                tags:
                %s""".formatted(id, name, tagLines.toString());
    }

    private BenchmarkScenario buildScenario(String id, String name, Map<String, Double> weights) {
        return BenchmarkScenario.builder()
                .id(id)
                .name(name)
                .userInput("测试输入")
                .dimensionWeights(weights)
                .timeoutSeconds(30)
                .expectedTokenBudget(500)
                .expectedStepCount(2)
                .build();
    }
}
