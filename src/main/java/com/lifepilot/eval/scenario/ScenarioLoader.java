package com.lifepilot.eval.scenario;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lifepilot.eval.config.EvalConfigProperties;

/**
 * 场景加载器 — 从 YAML 文件解析 BenchmarkScenario。
 *
 * <p>使用 Jackson YAML ObjectMapper 将配置目录下的 {@code *.yml} / {@code *.yaml}
 * 文件解析为 {@link BenchmarkScenario} 实例。支持按 ID 加载、按标签过滤、
 * 维度权重和校验及场景 ID 唯一性校验。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class ScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioLoader.class);

    /** 维度权重和校验容差。 */
    private static final double WEIGHT_SUM_TOLERANCE = 0.001;

    private final ObjectMapper yamlMapper;
    private final EvalConfigProperties config;

    public ScenarioLoader(EvalConfigProperties config) {
        this.config = config;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 从配置目录加载所有场景。
     *
     * @return 场景列表
     * @throws ScenarioLoadException 目录不存在或加载失败
     */
    public List<BenchmarkScenario> loadAll() {
        var directory = Path.of(config.getScenarioDirectory());
        if (!Files.exists(directory)) {
            throw new ScenarioLoadException("场景目录不存在: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new ScenarioLoadException("场景路径不是目录: " + directory);
        }

        var scenarios = new ArrayList<BenchmarkScenario>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{yml,yaml}")) {
            for (Path file : stream) {
                var scenario = loadFile(file);
                if (scenario != null) {
                    scenarios.add(scenario);
                }
            }
        } catch (IOException e) {
            throw new ScenarioLoadException("读取场景目录失败: " + directory, e);
        }

        validateScenarios(scenarios);
        return List.copyOf(scenarios);
    }

    /**
     * 按 ID 加载单个场景。
     *
     * @param scenarioId 场景 ID
     * @return 场景
     * @throws ScenarioLoadException 场景不存在或解析失败
     */
    public BenchmarkScenario loadById(String scenarioId) {
        var all = loadAll();
        return all.stream()
                .filter(s -> s.id().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new ScenarioLoadException("场景不存在: id=" + scenarioId));
    }

    /**
     * 按标签过滤加载场景。场景至少包含一个匹配标签即命中。
     *
     * @param tags 标签列表
     * @return 匹配的场景列表
     */
    public List<BenchmarkScenario> loadByTags(List<String> tags) {
        var all = loadAll();
        var tagSet = new HashSet<>(tags);
        return all.stream()
                .filter(s -> s.tags().stream().anyMatch(tagSet::contains))
                .toList();
    }

    /**
     * 校验场景有效性：维度权重和为 1.0（容差 0.001）、场景 ID 唯一。
     *
     * @param scenarios 待校验的场景列表
     * @throws ScenarioLoadException 校验不通过
     */
    void validateScenarios(List<BenchmarkScenario> scenarios) {
        // 校验场景 ID 唯一性
        var seenIds = new HashSet<String>();
        for (var scenario : scenarios) {
            if (!seenIds.add(scenario.id())) {
                throw new ScenarioLoadException("场景 ID 重复: " + scenario.id());
            }
        }

        // 校验维度权重和
        for (var scenario : scenarios) {
            var weights = scenario.dimensionWeights();
            if (weights.isEmpty()) {
                continue;
            }
            double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(sum - 1.0) > WEIGHT_SUM_TOLERANCE) {
                throw new ScenarioLoadException(
                        "场景 '%s' 维度权重和不为 1.0: 实际值=%.6f".formatted(scenario.id(), sum));
            }
        }
    }

    /**
     * 加载单个 YAML 文件为 BenchmarkScenario。空文件跳过并记录 WARN 日志。
     */
    private BenchmarkScenario loadFile(Path file) {
        try {
            var content = Files.readString(file);
            if (content.isBlank()) {
                log.warn("场景文件为空，已跳过: path={}", file);
                return null;
            }
            return yamlMapper.readValue(content, BenchmarkScenario.class);
        } catch (IOException e) {
            throw new ScenarioLoadException("YAML 解析失败: path=" + file + ", 错误=" + e.getMessage(), e);
        }
    }
}
