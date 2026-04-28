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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TranscriptSearchToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-04-28
 */
@DisplayName("transcript.search 检索")
class TranscriptSearchToolExecutor_检索测试 {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "session-test";

    private final SessionTranscriptRepository repository = mock(SessionTranscriptRepository.class);
    private final TranscriptSearchToolExecutor executor = new TranscriptSearchToolExecutor(repository);

    @Test
    void 按_toolId_过滤_只返回匹配的_tool_result() throws Exception {
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                toolResult("call-1", "web.search", true, "{\"results\":[{\"url\":\"a\"}]}",
                        Instant.parse("2026-04-28T08:00:00Z")),
                toolResult("call-2", "file.read", true, "{\"content\":\"hello\"}",
                        Instant.parse("2026-04-28T08:01:00Z")),
                toolResult("call-3", "web.search", false, "{\"error\":\"403\"}",
                        Instant.parse("2026-04-28T08:02:00Z"))
        ));

        ToolResult result = executor.execute(input(Map.of("toolId", "web.search")));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.data().get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> "web.search".equals(e.get("toolId")));
        // 倒序：最新先返
        assertThat(entries.get(0).get("callId")).isEqualTo("call-3");
        assertThat(entries.get(1).get("callId")).isEqualTo("call-1");
    }

    @Test
    void 按_callId_精确匹配_返回单条() throws Exception {
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                toolResult("call-a", "web.search", true, "{\"x\":1}", Instant.now()),
                toolResult("call-b", "file.read", true, "{\"y\":2}", Instant.now())
        ));

        ToolResult result = executor.execute(input(Map.of("callId", "call-b")));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.data().get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("toolId")).isEqualTo("file.read");
    }

    @Test
    void 按_keyword_模糊匹配_outputJson_大小写不敏感() throws Exception {
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                toolResult("c1", "web.search", true, "{\"url\":\"https://STEAMDB.info/\"}", Instant.now()),
                toolResult("c2", "web.search", true, "{\"url\":\"https://google.com/\"}", Instant.now())
        ));

        ToolResult result = executor.execute(input(Map.of("keyword", "steamdb")));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.data().get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("callId")).isEqualTo("c1");
    }

    @Test
    void 按_success_过滤_仅失败() throws Exception {
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                toolResult("c1", "web.fetch", true, "{}", Instant.now()),
                toolResult("c2", "web.fetch", false, "{\"error\":\"403\"}", Instant.now()),
                toolResult("c3", "web.fetch", false, "{\"error\":\"timeout\"}", Instant.now())
        ));

        ToolResult result = executor.execute(input(Map.of("success", false)));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.data().get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> Boolean.FALSE.equals(e.get("success")));
    }

    @Test
    void output_超过_maxPreviewChars_截断并提示() throws Exception {
        String longOutput = "x".repeat(500);
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                toolResult("c1", "tool.x", true, longOutput, Instant.now())
        ));

        ToolResult result = executor.execute(input(Map.of("maxPreviewChars", 100)));

        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.data().get("entries");
        var preview = (String) entries.get(0).get("outputPreview");
        assertThat(preview).startsWith("xxx").hasSizeGreaterThan(100);
        assertThat(preview).contains("transcript.get");
        assertThat(entries.get(0).get("outputTotalChars")).isEqualTo(500);
    }

    @Test
    void 缺_sessionId_上下文返回错误() {
        ToolResult result = executor.execute(new ToolInput(
                "transcript.search", Map.of(), JsonSchema.of(Map.of()), null, null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("会话上下文");
    }

    @Test
    void 非_tool_result_条目不被检索() throws Exception {
        when(repository.findBySessionId(eq(SESSION_ID))).thenReturn(List.of(
                userMessage("user-1", "你好", Instant.now()),
                toolResult("c1", "web.search", true, "{}", Instant.now())
        ));

        ToolResult result = executor.execute(input(Map.of()));

        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.data().get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("toolId")).isEqualTo("web.search");
    }

    private ToolInput input(Map<String, Object> params) {
        return new ToolInput("transcript.search", params, JsonSchema.of(Map.of()),
                null, Map.of(ToolContextKeys.SESSION_ID, SESSION_ID));
    }

    private SessionTranscriptEntryRow toolResult(String callId, String toolId, boolean success,
                                                  String outputJson, Instant createdAt) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("toolId", toolId);
        payload.put("callId", callId);
        payload.put("success", success);
        payload.put("outputJson", outputJson);
        return new SessionTranscriptEntryRow(
                UUID.randomUUID().toString(),
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

    private SessionTranscriptEntryRow userMessage(String id, String content, Instant createdAt) throws Exception {
        return new SessionTranscriptEntryRow(
                id, SESSION_ID, "main", "user", "user",
                null, null, true, true,
                MAPPER.writeValueAsString(Map.of("content", content)),
                0,
                createdAt
        );
    }
}
