package com.lifepilot.observability.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * TraceStep 序列化器 — 负责 TraceStep 与 JSON 之间的转换。
 *
 * <p>使用 Jackson ObjectMapper 进行序列化/反序列化，
 * 根据 step_type 字符串确定具体的 record 类型。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceStepSerializer {

    private final ObjectMapper objectMapper;

    public TraceStepSerializer() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 将 TraceStep 序列化为 JSON 字符串。
     *
     * @param step 追踪步骤
     * @return JSON 字符串
     * @throws TraceDeserializationException 序列化失败时抛出
     */
    public String serialize(TraceStep step) {
        try {
            return objectMapper.writeValueAsString(step);
        } catch (JsonProcessingException e) {
            throw new TraceDeserializationException(
                    "TraceStep 序列化失败: type=" + step.typeName(), e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 TraceStep。
     *
     * @param json     JSON 字符串
     * @param stepType 步骤类型标识（如 "llm_call"、"tool_call" 等）
     * @return 对应的 TraceStep 实例
     * @throws TraceDeserializationException 反序列化失败或类型未知时抛出
     */
    public TraceStep deserialize(String json, String stepType) {
        Class<? extends TraceStep> clazz = resolveType(stepType);
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new TraceDeserializationException(
                    "TraceStep 反序列化失败: type=" + stepType, e);
        }
    }

    /**
     * 将 TraceMetadata 序列化为 JSON 字符串。
     *
     * @param metadata 追踪元数据
     * @return JSON 字符串
     * @throws TraceDeserializationException 序列化失败时抛出
     */
    public String serializeMetadata(TraceMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new TraceDeserializationException("TraceMetadata 序列化失败", e);
        }
    }

    /**
     * 根据步骤类型名称解析对应的 record 类型。
     */
    private Class<? extends TraceStep> resolveType(String stepType) {
        return switch (stepType) {
            case "llm_call" -> LlmCallStep.class;
            case "tool_call" -> ToolCallStep.class;
            case "guardrail" -> GuardrailStep.class;
            case "state_transition" -> StateTransitionStep.class;
            case "evaluation" -> EvaluationStep.class;
            default -> throw new TraceDeserializationException(
                    "未知的 TraceStep 类型: " + stepType);
        };
    }
}
