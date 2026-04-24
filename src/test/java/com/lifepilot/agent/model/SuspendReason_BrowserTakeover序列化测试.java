package com.lifepilot.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.suspend.model.ResumePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SuspendReason.BrowserTakeover 与 ResumePayload.BrowserTakeoverCompleted 的
 * JSON 序列化与模式匹配穷举测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class SuspendReason_BrowserTakeover序列化测试 {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void BrowserTakeover_JSON_序列化反序列化_round_trip() throws Exception {
        var reason = new SuspendReason.BrowserTakeover(
                "task-login",
                "需要短信验证码",
                Instant.parse("2026-04-24T10:00:00Z")
        );

        String json = mapper.writeValueAsString(reason);
        SuspendReason.BrowserTakeover parsed = mapper.readValue(json, SuspendReason.BrowserTakeover.class);

        assertThat(parsed.sessionId()).isEqualTo("task-login");
        assertThat(parsed.reason()).isEqualTo("需要短信验证码");
        assertThat(parsed.requestedAt()).isEqualTo(Instant.parse("2026-04-24T10:00:00Z"));
    }

    @Test
    void 模式匹配_穷举_命中_BrowserTakeover() {
        SuspendReason reason = new SuspendReason.BrowserTakeover(
                "s", "r", Instant.parse("2026-04-24T10:00:00Z"));
        String label = switch (reason) {
            case SuspendReason.WorkflowWait _ -> "workflow";
            case SuspendReason.UserConfirmation _ -> "confirm";
            case SuspendReason.RemoteDelegation _ -> "remote";
            case SuspendReason.ScheduledWakeup _ -> "wakeup";
            case SuspendReason.ExternalDataWait _ -> "external";
            case SuspendReason.BrowserTakeover _ -> "browser_takeover";
        };
        assertThat(label).isEqualTo("browser_takeover");
    }

    @Test
    void BrowserTakeoverCompleted_JSON_round_trip() throws Exception {
        var payload = new ResumePayload.BrowserTakeoverCompleted("task-login", "已完成扫码");

        String json = mapper.writeValueAsString(payload);
        ResumePayload.BrowserTakeoverCompleted parsed =
                mapper.readValue(json, ResumePayload.BrowserTakeoverCompleted.class);

        assertThat(parsed.sessionId()).isEqualTo("task-login");
        assertThat(parsed.note()).isEqualTo("已完成扫码");
    }

    @Test
    void BrowserTakeoverCompleted_note_为null_时也可序列化() throws Exception {
        var payload = new ResumePayload.BrowserTakeoverCompleted("task-login", null);

        String json = mapper.writeValueAsString(payload);
        ResumePayload.BrowserTakeoverCompleted parsed =
                mapper.readValue(json, ResumePayload.BrowserTakeoverCompleted.class);

        assertThat(parsed.sessionId()).isEqualTo("task-login");
        assertThat(parsed.note()).isNull();
    }

    @Test
    void ResumePayload_模式匹配_穷举_命中_BrowserTakeoverCompleted() {
        ResumePayload payload = new ResumePayload.BrowserTakeoverCompleted("s", null);
        String label = switch (payload) {
            case ResumePayload.WorkflowResult _ -> "workflow";
            case ResumePayload.UserDecision _ -> "user";
            case ResumePayload.RemoteResult _ -> "remote";
            case ResumePayload.WakeupSignal _ -> "wakeup";
            case ResumePayload.DataReady _ -> "data";
            case ResumePayload.BrowserTakeoverCompleted _ -> "browser_takeover_completed";
        };
        assertThat(label).isEqualTo("browser_takeover_completed");
    }
}
