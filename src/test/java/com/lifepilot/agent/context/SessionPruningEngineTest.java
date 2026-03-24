package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

        assertThat(snapshot.section())
                .contains("tool.beta")
                .contains("tool.gamma")
                .doesNotContain("tool.alpha");
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

        assertThat(snapshot.section())
                .contains("tool.alpha")
                .contains("tool.beta")
                .contains("tool.gamma");
        assertThat(snapshot.selectedCount()).isEqualTo(3);
        assertThat(snapshot.pruningApplied()).isFalse();
    }

    @Test
    void pruneToolResults_预览长度受配置限制() {
        var config = buildConfig();
        config.getContext().getPruning().setToolResultPreviewChars(20);

        var engine = new SessionPruningEngine(config, new ObjectMapper());

        SessionPruningEngine.PruningSnapshot snapshot = engine.pruneToolResults(List.of(
                toolResultRow("1", "tool.long", """
                        {"summary":"这是一段非常长非常长非常长的工具结果摘要，用来验证预览截断逻辑"}
                        """.trim(), Instant.parse("2026-03-23T10:00:01Z"))
        ), 2048);

        assertThat(snapshot.section())
                .contains("tool.long")
                .contains("...")
                .doesNotContain("非常长非常长非常长的工具结果摘要，用来验证预览截断逻辑");
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

    private String quoteJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
