package com.lifepilot.workflow.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;

import com.lifepilot.workflow.model.WorkflowInputParam;

/**
 * 工作流输入参数校验器（无状态纯函数）。
 *
 * <p>校验用户提供的输入参数是否满足工作流定义的参数要求，
 * 包括必填检查、默认值填充、正则校验、多余参数保留。
 *
 * @author zsg
 * @since 2026-03-11
 */
public class InputValidator {

    private InputValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验用户输入是否满足工作流定义的参数要求。
     *
     * @param paramDefs  工作流定义的输入参数（WorkflowDefinition.inputs()）
     * @param userInputs 用户提供的输入参数（可为 null）
     * @return 校验结果
     */
    public static InputValidationResult validate(
            Map<String, WorkflowInputParam> paramDefs,
            @Nullable Map<String, Object> userInputs) {

        Map<String, Object> safeInputs = userInputs != null ? userInputs : Map.of();

        // 空定义透传（P3）
        if (paramDefs == null || paramDefs.isEmpty()) {
            return new InputValidationResult(true, List.of(), List.of(), new HashMap<>(safeInputs));
        }

        Map<String, Object> mergedInputs = new HashMap<>(safeInputs);
        List<String> missingParams = new ArrayList<>();

        for (var entry : paramDefs.entrySet()) {
            String paramName = entry.getKey();
            WorkflowInputParam paramDef = entry.getValue();

            if (mergedInputs.containsKey(paramName)) {
                // 用户已提供，保留用户值
                continue;
            }

            if (paramDef.defaultValue() != null) {
                // 用户未提供但有默认值，填入默认值（P2）
                mergedInputs.put(paramName, paramDef.defaultValue());
            } else if (paramDef.required()) {
                // 用户未提供、无默认值、且必填 → 缺失（P1）
                missingParams.add(paramName);
            }
        }

        // 正则校验（P5）：对已有值的参数，检查 validationPattern
        List<String> validationErrors = new ArrayList<>();
        for (var entry : paramDefs.entrySet()) {
            String paramName = entry.getKey();
            WorkflowInputParam paramDef = entry.getValue();

            if (paramDef.validationPattern() != null && mergedInputs.containsKey(paramName)) {
                Object value = mergedInputs.get(paramName);
                if (value instanceof String strValue) {
                    if (!strValue.matches(paramDef.validationPattern())) {
                        String msg = paramDef.validationMessage() != null
                                ? paramDef.validationMessage()
                                : "参数 " + paramName + " 不符合校验规则: " + paramDef.validationPattern();
                        validationErrors.add(msg);
                    }
                }
            }
        }

        // 多余参数自动保留在 mergedInputs 中（P4）
        boolean valid = missingParams.isEmpty() && validationErrors.isEmpty();
        return new InputValidationResult(valid, List.copyOf(missingParams), List.copyOf(validationErrors), mergedInputs);
    }
}
