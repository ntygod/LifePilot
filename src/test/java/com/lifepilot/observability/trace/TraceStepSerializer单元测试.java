package com.lifepilot.observability.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TraceStepSerializer 单元测试。
 *
 * <p>验证五种 TraceStep record 与 JSON 之间的序列化 / 反序列化 round-trip，
 * 以及 TraceMetadata 的序列化行为和异常场景。</p>
 */
class TraceStepSerializer单元测试 {

    private final TraceStepSerializer serializer = new TraceStepSerializer();

    @Test
    @DisplayName("LlmCallStep_roundtrip序列化与反序列化()")
    void LlmCallStep_roundtrip序列化与反序列化() {
        LlmCallStep step = new LlmCallStep(
                0,
                Instant.parse("2026-03-01T10:15:30Z"),
                Duration.ofMillis(120),
                "openai",
                "gpt-4.1",
                "chat",
                128,
                64,
                Duration.ofMillis(80),
                true,
                0.7,
                "stop"
        );

        String json = serializer.serialize(step);
        TraceStep restored = serializer.deserialize(json, "llm_call");

        assertThat(restored).isInstanceOf(LlmCallStep.class);
        assertThat(restored).usingRecursiveComparison().isEqualTo(step);
    }

    @Test
    @DisplayName("ToolCallStep_roundtrip序列化与反序列化()")
    void ToolCallStep_roundtrip序列化与反序列化() {
        ToolCallStep step = new ToolCallStep(
                1,
                Instant.parse("2026-03-01T11:00:00Z"),
                Duration.ofSeconds(1),
                "weather",
                "query",
                "{\"city\":\"上海\"}",
                "{\"temp\":18}",
                true,
                null,
                com.lifepilot.observability.guardrail.RiskLevel.LOW
        );

        String json = serializer.serialize(step);
        TraceStep restored = serializer.deserialize(json, "tool_call");

        assertThat(restored).isInstanceOf(ToolCallStep.class);
        assertThat(restored).usingRecursiveComparison().isEqualTo(step);
    }

    @Test
    @DisplayName("GuardrailStep_roundtrip序列化与反序列化()")
    void GuardrailStep_roundtrip序列化与反序列化() {
        GuardrailStep step = new GuardrailStep(
                2,
                Instant.parse("2026-03-01T12:00:00Z"),
                Duration.ZERO,
                "default-policy",
                "input",
                false,
                "命中敏感词",
                com.lifepilot.observability.guardrail.RiskLevel.HIGH,
                com.lifepilot.observability.guardrail.ApprovalMode.USER_CONFIRM
        );

        String json = serializer.serialize(step);
        TraceStep restored = serializer.deserialize(json, "guardrail");

        assertThat(restored).isInstanceOf(GuardrailStep.class);
        assertThat(restored).usingRecursiveComparison().isEqualTo(step);
    }

    @Test
    @DisplayName("StateTransitionStep_roundtrip序列化与反序列化()")
    void StateTransitionStep_roundtrip序列化与反序列化() {
        StateTransitionStep step = new StateTransitionStep(
                3,
                Instant.parse("2026-03-01T13:00:00Z"),
                Duration.ofMillis(5),
                "planning",
                "executing",
                "tool_call",
                "调用天气查询工具"
        );

        String json = serializer.serialize(step);
        TraceStep restored = serializer.deserialize(json, "state_transition");

        assertThat(restored).isInstanceOf(StateTransitionStep.class);
        assertThat(restored).usingRecursiveComparison().isEqualTo(step);
    }

    @Test
    @DisplayName("EvaluationStep_roundtrip序列化与反序列化_集合字段不可变()")
    void EvaluationStep_roundtrip序列化与反序列化_集合字段不可变() {
        EvaluationStep step = new EvaluationStep(
                4,
                Instant.parse("2026-03-01T14:00:00Z"),
                Duration.ofSeconds(2),
                0.9,
                0.8,
                0.7,
                1.0,
                0.6,
                0.82,
                List.of("工具选择失败 1 次"),
                List.of("减少冗余工具调用")
        );

        String json = serializer.serialize(step);
        TraceStep restored = serializer.deserialize(json, "evaluation");

        assertThat(restored).isInstanceOf(EvaluationStep.class);
        assertThat(restored).usingRecursiveComparison().isEqualTo(step);

        EvaluationStep restoredEval = (EvaluationStep) restored;
        assertThat(restoredEval.violations()).isUnmodifiable();
        assertThat(restoredEval.suggestions()).isUnmodifiable();
    }

    @Test
    @DisplayName("TraceMetadata_roundtrip序列化与反序列化_集合字段不可变()")
    void TraceMetadata_roundtrip序列化与反序列化_集合字段不可变() {
        TraceMetadata metadata = new TraceMetadata(
                "web",
                "user-123",
                "1.0.0",
                Map.of("env", "dev", "region", "cn-shanghai")
        );

        String json = serializer.serializeMetadata(metadata);

        // 使用 Jackson ObjectMapper 反序列化以验证 JSON 结构正确性
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        TraceMetadata restored;
        try {
            restored = objectMapper.readValue(json, TraceMetadata.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(restored.channelType()).isEqualTo("web");
        assertThat(restored.userId()).isEqualTo("user-123");
        assertThat(restored.clientVersion()).isEqualTo("1.0.0");
        assertThat(restored.tags()).containsEntry("env", "dev").containsEntry("region", "cn-shanghai");
        assertThat(restored.tags()).isUnmodifiable();
    }

    @Test
    @DisplayName("未知stepType_反序列化抛出TraceDeserializationException()")
    void 未知stepType_反序列化抛出TraceDeserializationException() {
        String json = "{}";

        assertThatThrownBy(() -> serializer.deserialize(json, "unknown_type"))
                .isInstanceOf(TraceDeserializationException.class)
                .hasMessageContaining("未知的 TraceStep 类型");
    }

    @Test
    @DisplayName("JSON格式错误_序列化器抛出TraceDeserializationException()")
    void JSON格式错误_序列化器抛出TraceDeserializationException() {
        String invalidJson = "{ this is not valid json }";

        assertThatThrownBy(() -> serializer.deserialize(invalidJson, "llm_call"))
                .isInstanceOf(TraceDeserializationException.class)
                .hasMessageContaining("TraceStep 反序列化失败");
    }
}

