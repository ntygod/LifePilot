package com.lifepilot.eval.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.eval.scenario.BenchmarkScenario;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数合法性评估器。
 *
 * <p>对每个工具调用的 toolInput 进行 JSON Schema 校验。
 * 评分 = 校验通过的工具调用数 / 总工具调用数。
 * 若无工具调用，评分为 1.0。
 * 若工具未在注册中心找到，计为无效并记录违规。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public final class ParameterValidityEvaluator implements DimensionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ParameterValidityEvaluator.class);
    private static final String DIMENSION_NAME = "parameterValidity";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DynamicToolRegistry toolRegistry;

    public ParameterValidityEvaluator(DynamicToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String dimensionName() {
        return DIMENSION_NAME;
    }

    @Override
    public DimensionScore evaluate(List<TraceStep> steps, BenchmarkScenario scenario) {
        // 筛选工具调用步骤
        List<ToolCallStep> toolSteps = steps.stream()
                .filter(s -> s instanceof ToolCallStep)
                .map(s -> (ToolCallStep) s)
                .toList();

        if (toolSteps.isEmpty()) {
            return new DimensionScore(DIMENSION_NAME, 1.0, List.of(), List.of());
        }

        int validCount = 0;
        var violations = new ArrayList<String>();
        var suggestions = new ArrayList<String>();

        for (ToolCallStep step : toolSteps) {
            String toolId = step.toolId();
            var toolOpt = toolRegistry.resolve(toolId);

            if (toolOpt.isEmpty()) {
                violations.add("步骤 %d: 工具 '%s' 未在注册中心找到".formatted(step.stepIndex(), toolId));
                suggestions.add("确认工具 '%s' 已正确注册到 DynamicToolRegistry".formatted(toolId));
                continue;
            }

            ToolContract tool = toolOpt.get();
            var schema = tool.inputSchema();

            if (schema == null || schema.toMap().isEmpty()) {
                // 无 Schema 定义，视为通过
                validCount++;
                continue;
            }

            // 解析 toolInput JSON 为 Map
            String toolInput = step.inputJson();
            if (toolInput == null || toolInput.isBlank()) {
                // 无输入参数，校验 required 字段
                var errors = schema.validate(Map.of());
                if (errors.isEmpty()) {
                    validCount++;
                } else {
                    violations.add("步骤 %d: 工具 '%s' 输入为空但 Schema 要求必填字段: %s"
                            .formatted(step.stepIndex(), toolId, errors));
                }
                continue;
            }

            try {
                Map<String, Object> params = OBJECT_MAPPER.readValue(toolInput, MAP_TYPE);
                var errors = schema.validate(params);
                if (errors.isEmpty()) {
                    validCount++;
                } else {
                    violations.add("步骤 %d: 工具 '%s' 参数校验失败: %s"
                            .formatted(step.stepIndex(), toolId, errors));
                }
            } catch (Exception e) {
                violations.add("步骤 %d: 工具 '%s' 输入 JSON 解析失败: %s"
                        .formatted(step.stepIndex(), toolId, e.getMessage()));
                log.debug("工具输入 JSON 解析失败: toolId={}, input={}", toolId, toolInput, e);
            }
        }

        double score = (double) validCount / toolSteps.size();
        return new DimensionScore(DIMENSION_NAME, score,
                List.copyOf(violations), List.copyOf(suggestions));
    }
}
