package com.lifepilot.meta.infra.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository.SessionTranscriptEntryRow;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TranscriptGetToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-04-28
 */
@DisplayName("transcript.get 读取")
class TranscriptGetToolExecutor_读取测试 {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "session-test";

    private final SessionTranscriptRepository repository = mock(SessionTranscriptRepository.class);
    private final TranscriptGetToolExecutor executor = new TranscriptGetToolExecutor(repository);

    @Test
    void 按_entryId_取完整_outputJson() throws Exception {
        var row = toolResult("entry-1", "call-x", "web.search", true, "{\"hits\":42}", Instant.now());
        when(repository.findById(eq("entry-1"))).thenReturn(Optional.of(row));

        ToolResult result = executor.execute(input(Map.of("entryId", "entry-1")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("toolId")).isEqualTo("web.search");
        assertThat(result.data().get("callId")).isEqualTo("call-x");
        assertThat(result.data().get("outputJson")).isEqualTo("{\"hits\":42}");
        assertThat(result.data().get("truncated")).isEqualTo(false);
    }

    @Test
    void 按_callId_取倒序最新一条() throws Exception {
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                toolResult("e1", "call-shared", "tool.x", false, "{\"first\":true}", Instant.parse("2026-04-28T08:00:00Z")),
                toolResult("e2", "call-shared", "tool.x", true, "{\"second\":true}", Instant.parse("2026-04-28T08:01:00Z"))
        ));

        ToolResult result = executor.execute(input(Map.of("callId", "call-shared")));

        assertThat(result.isSuccess()).isTrue();
        // 倒序找：拿到最后一次（重试成功的那条）
        assertThat(result.data().get("entryId")).isEqualTo("e2");
        assertThat(result.data().get("success")).isEqualTo(true);
    }

    @Test
    void 跨会话_entryId_拒绝读取() throws Exception {
        var foreign = new SessionTranscriptEntryRow(
                "foreign-entry", "other-session", "main", "tool_result", "tool",
                null, null, true, false,
                MAPPER.writeValueAsString(Map.of("toolId", "x", "outputJson", "{}")),
                0, Instant.now());
        when(repository.findById(eq("foreign-entry"))).thenReturn(Optional.of(foreign));

        ToolResult result = executor.execute(input(Map.of("entryId", "foreign-entry")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("不属于当前会话");
    }

    @Test
    void 非_tool_result_类型拒绝() throws Exception {
        var row = new SessionTranscriptEntryRow(
                "msg-1", SESSION_ID, "main", "user", "user",
                null, null, true, true,
                MAPPER.writeValueAsString(Map.of("content", "hi")),
                0, Instant.now());
        when(repository.findById(eq("msg-1"))).thenReturn(Optional.of(row));

        ToolResult result = executor.execute(input(Map.of("entryId", "msg-1")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("仅支持读取 tool_result");
    }

    @Test
    void output_超过_maxChars_截断并标记_truncated() throws Exception {
        String longOutput = "y".repeat(2000);
        var row = toolResult("e1", "c1", "tool.big", true, longOutput, Instant.now());
        when(repository.findById(eq("e1"))).thenReturn(Optional.of(row));

        ToolResult result = executor.execute(input(Map.of("entryId", "e1", "maxChars", 500)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("truncated")).isEqualTo(true);
        assertThat((String) result.data().get("outputJson"))
                .startsWith("yyy").contains("内容已截断");
    }

    @Test
    void 缺_entryId_和_callId_报错() {
        ToolResult result = executor.execute(input(Map.of()));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("entryId 或 callId");
    }

    @Test
    void 缺_sessionId_上下文返回错误() {
        ToolResult result = executor.execute(new ToolInput(
                "transcript.get", Map.of("entryId", "x"),
                JsonSchema.of(Map.of()), null, null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("会话上下文");
    }

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("transcript.get", params, JsonSchema.of(Map.of()),
                null, Map.of(ToolContextKeys.SESSION_ID, SESSION_ID));
    }

    private SessionTranscriptEntryRow toolResult(String entryId, String callId, String toolId, boolean success,
                                                  String outputJson, Instant createdAt) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("toolId", toolId);
        payload.put("callId", callId);
        payload.put("success", success);
        payload.put("outputJson", outputJson);
        return new SessionTranscriptEntryRow(
                entryId == null ? UUID.randomUUID().toString() : entryId,
                SESSION_ID,
                "main",
                "tool_result",
                "tool",
                null, null, true, false,
                MAPPER.writeValueAsString(payload),
                0,
                createdAt
        );
    }
}
