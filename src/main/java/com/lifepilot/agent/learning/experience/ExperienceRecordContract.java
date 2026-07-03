package com.lifepilot.agent.learning.experience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 经验记录 LLM 输出契约校验。
 *
 * @author zsg
 * @since 2026-06-30
 */
public final class ExperienceRecordContract {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ExperienceRecordContract() {
    }

    public static void validateLlmResponse(String content, String context) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException(context + " LLM 响应不能为空");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(content);
            if (!node.isObject()) {
                throw new IllegalStateException(context + " LLM 响应顶层必须是 JSON 对象");
            }
            requireText(node, "scenario", context);
            requireText(node, "strategy", context);
            JsonNode success = node.get("success");
            if (success == null || !success.isBoolean()) {
                throw new IllegalStateException(context + " LLM 响应 success 必须是 boolean");
            }
            requireTextArray(node, "lessons", context);
            requireTextArray(node, "applicableConditions", context);
            requireTextArray(node, "toolsUsed", context);
            requireFailureAttribution(node, success.booleanValue(), context);
            requireNumberInRange(node, "effectivenessScore", context);
            requireNonNegativeInteger(node, "injectionCount", context);
            requireNonNegativeInteger(node, "positiveOutcomes", context);
            requireNonNegativeInteger(node, "negativeOutcomes", context);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(context + " LLM 响应 JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    private static void requireText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(context + " LLM 响应缺少 " + field);
        }
        if (!value.asText().equals(value.asText().trim())) {
            throw new IllegalStateException(context + " LLM 响应 " + field + " 不能包含首尾空白");
        }
    }

    private static void requireTextArray(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException(context + " LLM 响应 " + field + " 必须是数组");
        }
        for (int i = 0; i < value.size(); i++) {
            JsonNode item = value.get(i);
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException(context + " LLM 响应 " + field + "[" + i + "] 必须是非空字符串");
            }
            if (!item.asText().equals(item.asText().trim())) {
                throw new IllegalStateException(context + " LLM 响应 " + field + "[" + i + "] 不能包含首尾空白");
            }
        }
    }

    private static void requireFailureAttribution(JsonNode node, boolean success, String context) {
        JsonNode value = node.get("failureAttribution");
        if (value == null) {
            throw new IllegalStateException(context + " LLM 响应缺少 failureAttribution");
        }
        if (success) {
            if (!value.isNull()) {
                throw new IllegalStateException(context + " LLM 响应成功时 failureAttribution 必须为 null");
            }
            return;
        }
        if (!value.isTextual()) {
            throw new IllegalStateException(context + " LLM 响应失败时 failureAttribution 必须是字符串");
        }
        String attribution = value.asText();
        if (!"strategy".equals(attribution) && !"system".equals(attribution)) {
            throw new IllegalStateException(context + " LLM 响应 failureAttribution 只能是 strategy 或 system");
        }
    }

    private static void requireNumberInRange(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException(context + " LLM 响应 " + field + " 必须是数字");
        }
        double number = value.asDouble();
        if (!Double.isFinite(number) || number < 0.0d || number > 1.0d) {
            throw new IllegalStateException(context + " LLM 响应 " + field + " 必须在 [0,1] 范围内");
        }
    }

    private static void requireNonNegativeInteger(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalStateException(context + " LLM 响应 " + field + " 必须是整数");
        }
        if (value.longValue() < 0L) {
            throw new IllegalStateException(context + " LLM 响应 " + field + " 不能小于 0");
        }
    }
}
