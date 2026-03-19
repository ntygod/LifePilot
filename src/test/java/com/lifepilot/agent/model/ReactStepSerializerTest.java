package com.lifepilot.agent.model;

import com.lifepilot.agent.suspend.model.ResumePayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReactStepSerializer 单元测试。
 *
 * @author zsg
 * @since 2026-03-19
 */
class ReactStepSerializerTest {

    @Test
    void serialize_空列表_返回空() {
        assertEquals(List.of(), ReactStepSerializer.serialize(List.of()));
        assertEquals(List.of(), ReactStepSerializer.serialize(null));
    }

    @Test
    void serialize_Thought步骤_包含type和content() {
        var steps = List.<ReactStep>of(new ReactStep.Thought("这是一段思考"));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals(1, result.size());
        assertEquals("THOUGHT", result.getFirst().get("type"));
        assertEquals(0, result.getFirst().get("index"));
        assertEquals("这是一段思考", result.getFirst().get("content"));
    }

    @Test
    void serialize_ToolCall步骤_包含toolId和inputSummary() {
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall("web-search", "网页搜索", "{\"query\":\"test\"}", 150));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("TOOL_CALL", result.getFirst().get("type"));
        assertEquals("web-search", result.getFirst().get("toolId"));
        assertEquals("网页搜索", result.getFirst().get("toolName"));
        assertEquals("{\"query\":\"test\"}", result.getFirst().get("inputSummary"));
        assertEquals(150L, result.getFirst().get("latencyMs"));
    }

    @Test
    void serialize_ToolCall步骤_toolName为null时不包含该字段() {
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall("unknown-tool", null, "{}", 50));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("unknown-tool", result.getFirst().get("toolId"));
        assertFalse(result.getFirst().containsKey("toolName"));
    }

    @Test
    void serialize_Observation步骤_包含success和outputSummary() {
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("web-search", "网页搜索", true, "搜索结果内容", 42));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("OBSERVATION", result.getFirst().get("type"));
        assertEquals("web-search", result.getFirst().get("toolId"));
        assertEquals(true, result.getFirst().get("success"));
        assertEquals("搜索结果内容", result.getFirst().get("outputSummary"));
        assertEquals(42, result.getFirst().get("tokensUsed"));
    }

    @Test
    void serialize_Answer步骤_包含content() {
        var steps = List.<ReactStep>of(new ReactStep.Answer("最终回答"));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("ANSWER", result.getFirst().get("type"));
        assertEquals("最终回答", result.getFirst().get("content"));
    }

    @Test
    void serialize_Suspend步骤_包含reason和suspendedAt() {
        var now = Instant.now();
        var steps = List.<ReactStep>of(
                new ReactStep.Suspend(
                        new SuspendReason.UserConfirmation("tool-1", "{}", "HIGH", "confirm-1"),
                        now, 3));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("SUSPEND", result.getFirst().get("type"));
        assertNotNull(result.getFirst().get("reason"));
        assertEquals(now.toString(), result.getFirst().get("suspendedAt"));
    }

    @Test
    void serialize_Resume步骤_包含resumedAt和suspendDurationMs() {
        var now = Instant.now();
        var steps = List.<ReactStep>of(
                new ReactStep.Resume(
                        new ResumePayload.UserDecision("confirm-1", true, "已确认"),
                        now, Duration.ofSeconds(30)));
        var result = ReactStepSerializer.serialize(steps);

        assertEquals("RESUME", result.getFirst().get("type"));
        assertEquals(now.toString(), result.getFirst().get("resumedAt"));
        assertEquals(30000L, result.getFirst().get("suspendDurationMs"));
    }

    @Test
    void truncate_超长Thought_截断到500字符() {
        String longContent = "A".repeat(600);
        var steps = List.<ReactStep>of(new ReactStep.Thought(longContent));
        var result = ReactStepSerializer.serialize(steps);

        String content = (String) result.getFirst().get("content");
        assertEquals(503, content.length()); // 500 + "..."
        assertTrue(content.endsWith("..."));
    }

    @Test
    void truncate_超长inputJson_截断到200字符() {
        String longInput = "{" + "x".repeat(300) + "}";
        var steps = List.<ReactStep>of(new ReactStep.ToolCall("tool", null, longInput, 100));
        var result = ReactStepSerializer.serialize(steps);

        String inputSummary = (String) result.getFirst().get("inputSummary");
        assertEquals(203, inputSummary.length()); // 200 + "..."
        assertTrue(inputSummary.endsWith("..."));
    }

    @Test
    void truncate_超长output_截断到300字符() {
        String longOutput = "O".repeat(400);
        var steps = List.<ReactStep>of(
                new ReactStep.Observation("tool", null, true, longOutput, 10));
        var result = ReactStepSerializer.serialize(steps);

        String outputSummary = (String) result.getFirst().get("outputSummary");
        assertEquals(303, outputSummary.length()); // 300 + "..."
        assertTrue(outputSummary.endsWith("..."));
    }

    @Test
    void truncate_null文本_返回空字符串() {
        assertEquals("", ReactStepSerializer.truncate(null, 100));
    }

    @Test
    void serialize_完整ReAct循环_索引递增() {
        var steps = List.<ReactStep>of(
                new ReactStep.Thought("思考问题"),
                new ReactStep.ToolCall("search", "搜索", "{}", 100),
                new ReactStep.Observation("search", "搜索", true, "结果", 20),
                new ReactStep.Answer("最终回答")
        );
        var result = ReactStepSerializer.serialize(steps);

        assertEquals(4, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(i, result.get(i).get("index"));
        }
        assertEquals("THOUGHT", result.get(0).get("type"));
        assertEquals("TOOL_CALL", result.get(1).get("type"));
        assertEquals("OBSERVATION", result.get(2).get("type"));
        assertEquals("ANSWER", result.get(3).get("type"));
    }
}
