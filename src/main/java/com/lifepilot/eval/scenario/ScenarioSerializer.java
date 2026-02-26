package com.lifepilot.eval.scenario;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * 场景序列化器 — 将 {@link BenchmarkScenario} 序列化为 YAML 字符串。
 *
 * <p>使用 Jackson YAML ObjectMapper，排除 null 字段，不输出文档起始标记（{@code ---}）。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class ScenarioSerializer {

    private final ObjectMapper yamlMapper;

    public ScenarioSerializer() {
        var yamlFactory = new YAMLFactory();
        yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 序列化场景为 YAML 字符串。
     *
     * @param scenario 场景
     * @return YAML 字符串
     * @throws ScenarioLoadException 序列化失败
     */
    public String serialize(BenchmarkScenario scenario) {
        try {
            return yamlMapper.writeValueAsString(scenario);
        } catch (JsonProcessingException e) {
            throw new ScenarioLoadException("场景序列化失败: id=" + scenario.id(), e);
        }
    }
}
