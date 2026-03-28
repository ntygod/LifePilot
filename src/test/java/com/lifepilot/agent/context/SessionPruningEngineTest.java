package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionPruningEngine 单元测试。
 *
 * @author zsg
 * @since 2026-03-23
 */
class SessionPruningEngineTest {

    @Test
    void pruneToolResults_recentOnly模式按最近条数裁剪() {
        var config = buildConfig();
        config.getContext().getPruning().setToolResultMode("recent_only");
        config.getContext().getPruning().setRecentToolResultLimit(2);

        var engine = new SessionPruningEngine(config, new ObjectMapper());

        SessionPruningEngine.PruningSnapshot snapshot = engine.pruneToolResults(List.of(
                toolResultRow("1", "tool.alpha", "{\"summary\":\"alpha\"}", Instant.parse("2026-03-23T10:00:01Z")),
                toolResultRow("2", "tool.beta", "{\"summary\":\"beta\"}", Instant.parse("2026-03-23T10:00:02Z")),
                toolResultRow("3", "tool.gamma", "{\"summary\":\"gamma\"}", Instant.parse("2026-03-23T10:00:03Z"))
        ), 2048);

        assertThat(snapshot.selectedEntryIds())
                .containsExactlyInAnyOrder("2", "3")
                .doesNotContain("1");
        assertThat(snapshot.selectedCount()).isEqualTo(2);
        assertThat(snapshot.totalCount()).isEqualTo(3);
        assertThat(snapshot.pruningApplied()).isTrue();
    }

    @Test
    void pruneToolResults_off模式忽略最近条数限制但仍受token预算约束() {
        var config = buildConfig();
        config.getContext().getPruning().setToolResultMode("off");
        config.getContext().getPruning().setRecentToolResultLimit(1);

        var engine = new SessionPruningEngine(config, new ObjectMapper());

        SessionPruningEngine.PruningSnapshot snapshot = engine.pruneToolResults(List.of(
                toolResultRow("1", "tool.alpha", "{\"summary\":\"alpha\"}", Instant.parse("2026-03-23T10:00:01Z")),
                toolResultRow("2", "tool.beta", "{\"summary\":\"beta\"}", Instant.parse("2026-03-23T10:00:02Z")),
                toolResultRow("3", "tool.gamma", "{\"summary\":\"gamma\"}", Instant.parse("2026-03-23T10:00:03Z"))
        ), 2048);

        assertThat(snapshot.selectedEntryIds())
                .containsExactlyInAnyOrder("1", "2", "3");
        assertThat(snapshot.selectedCount()).isEqualTo(3);
        assertThat(snapshot.pruningApplied()).isFalse();
    }

    @Test
    void pruneToolResults_recentOnly模式会额外保留最近失败结果() {
        var config = buildConfig();
        config.getContext().getPruning().setToolResultMode("recent_only");
        config.getContext().getPruning().setRecentToolResultLimit(1);
        config.getContext().getPruning().setFailedToolResultLimit(1);

        var engine = new SessionPruningEngine(config, new ObjectMapper());

        SessionPruningEngine.PruningSnapshot snapshot = engine.pruneToolResults(List.of(
                toolResultRow("1", "tool.alpha", "{\"summary\":\"alpha\"}", true, Instant.parse("2026-03-23T10:00:01Z")),
                toolResultRow("2", "tool.beta", "{\"summary\":\"beta fail\"}", false, Instant.parse("2026-03-23T10:00:02Z")),
                toolResultRow("3", "tool.gamma", "{\"summary\":\"gamma\"}", true, Instant.parse("2026-03-23T10:00:03Z"))
        ), 2048);

        assertThat(snapshot.selectedEntryIds())
                .containsExactlyInAnyOrder("2", "3")
                .doesNotContain("1");
        assertThat(snapshot.selectedCount()).isEqualTo(2);
        assertThat(snapshot.pruningApplied()).isTrue();
    }

    @Test
    void formatToolResultPreview_长文本时会软裁剪并保留首尾信息() {
        var config = buildConfig();
        config.getContext().getPruning().setToolResultPreviewChars(40);

        var engine = new SessionPruningEngine(config, new ObjectMapper());

        String preview = engine.formatToolResultPreview(Map.of(
                "outputJson", """
                        {"summary":"这是第一段内容，用来观察前缀保留。这里继续补充一些中间内容，确保文本足够长。最后再补一段结尾信息，用来验证尾部保留。"}
                        """.trim()
        ));

        assertThat(preview)
                .contains("这是第一段内容")
                .contains("尾部保留")
                .contains("中间省略");
    }

    @Test
    void formatToolResultPreview_超长文本时会硬清空为占位符() {
        var config = buildConfig();
        config.getContext().getPruning().setToolResultPreviewChars(20);

        var engine = new SessionPruningEngine(config, new ObjectMapper());
        String longText = "很长的结果".repeat(80);

        String preview = engine.formatToolResultPreview(Map.of(
                "outputJson", "{\"summary\":\"" + longText + "\"}"
        ));

        assertThat(preview)
                .contains("结果过长已省略")
                .contains("原始约");
    }

    private AgentConfigProperties buildConfig() {
        var config = new AgentConfigProperties();
        config.getContext().setMaxContextTokens(4096);
        config.getContext().setOutputReservedTokens(512);
        config.getContext().getTokenAllocation().setToolResultPercent(20);
        return config;
    }

    private SessionTranscriptRepository.SessionTranscriptEntryRow toolResultRow(
            String id,
            String toolId,
            String outputJson,
            Instant createdAt
    ) {
        return new SessionTranscriptRepository.SessionTranscriptEntryRow(
                id,
                "session-1",
                "main",
                "tool_result",
                "tool",
                "turn-1",
                "trace-1",
                true,
                false,
                """
                {"toolId":"%s","callId":"call-%s","success":true,"outputJson":%s}
                """.formatted(toolId, id, quoteJson(outputJson)).trim(),
                0,
                createdAt
        );
    }

    private SessionTranscriptRepository.SessionTranscriptEntryRow toolResultRow(
            String id,
            String toolId,
            String outputJson,
            boolean success,
            Instant createdAt
    ) {
        return new SessionTranscriptRepository.SessionTranscriptEntryRow(
                id,
                "session-1",
                "main",
                "tool_result",
                "tool",
                "turn-1",
                "trace-1",
                true,
                false,
                """
                {"toolId":"%s","callId":"call-%s","success":%s,"outputJson":%s}
                """.formatted(toolId, id, success, quoteJson(outputJson)).trim(),
                0,
                createdAt
        );
    }

    private String quoteJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
