package com.lifepilot.workflow.engine;

import java.util.List;
import java.util.Map;

/**
 * 工作流输入校验结果。
 *
 * @param valid         校验是否通过
 * @param missingParams 缺失的必填参数名称列表
 * @param mergedInputs  合并默认值后的完整输入 Map
 * @author zsg
 * @since 2026-03-11
 */
public record InputValidationResult(
        boolean valid,
        List<String> missingParams,
        Map<String, Object> mergedInputs
) {}
