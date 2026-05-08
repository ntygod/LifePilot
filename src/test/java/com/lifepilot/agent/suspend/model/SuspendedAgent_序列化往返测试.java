package com.lifepilot.agent.suspend.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.SourceKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SuspendedAgent 完整序列化往返测试。
 *
 * <p>覆盖生产路径下 BrowserTakeover 等 6 种 SuspendReason 的"挂起 → JSON →
 * SQLite 落库 → load → toAgentState"全链路 round-trip：</p>
 * <ul>
 *   <li>{@link InteractionSource} 派生属性 {@code isAutonomous} / {@code isChannel}
 *       不应污染 stateJson</li>
 *   <li>{@link SuspendReason} sealed 子类型必须保留具体类型而非退化为接口</li>
 *   <li>{@link com.lifepilot.agent.suspend.model.ResumePayload} 同款 round-trip</li>
 *   <li>{@link ReactStep} sealed 子类型（含 Suspend / Resume 嵌套子类型）应可恢复</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-25
 */
class SuspendedAgent_序列化往返测试 {

    /**
     * 与 Spring Boot 默认行为一致的 ObjectMapper：
     * - {@code findAndRegisterModules} 加载 JavaTimeModule，处理 Instant / Duration
     * - 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES} 仍开启时也能通过，验证 isAutonomous 不会泄漏
     */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentConfigProperties config = new AgentConfigProperties();

    @Test
    void BrowserTakeover_完整_round_trip_保留具体子类型() throws Exception {
        var requestedAt = Instant.parse("2026-04-25T10:00:00Z");
        var original = buildSuspended(
                "trace-browser",
                new SuspendReason.BrowserTakeover("browser-session-A", "需要扫码登录", requestedAt, 600));

        var restored = roundTrip(original);

        assertThat(restored.suspendReason()).isInstanceOf(SuspendReason.BrowserTakeover.class);
        var br = (SuspendReason.BrowserTakeover) restored.suspendReason();
        assertThat(br.sessionId()).isEqualTo("browser-session-A");
        assertThat(br.reason()).isEqualTo("需要扫码登录");
        assertThat(br.requestedAt()).isEqualTo(requestedAt);
        assertThat(br.timeoutSeconds()).isEqualTo(600);
    }

    @Test
    void WorkflowWait_round_trip_保留具体子类型() throws Exception {
        var original = buildSuspended(
                "trace-wf",
                new SuspendReason.WorkflowWait("exec-1", "wf-1", "审批流"));

        var restored = roundTrip(original);

        assertThat(restored.suspendReason()).isInstanceOf(SuspendReason.WorkflowWait.class);
        var ww = (SuspendReason.WorkflowWait) restored.suspendReason();
        assertThat(ww.executionId()).isEqualTo("exec-1");
        assertThat(ww.workflowId()).isEqualTo("wf-1");
        assertThat(ww.workflowName()).isEqualTo("审批流");
    }

    @Test
    void UserConfirmation_round_trip_保留具体子类型() throws Exception {
        var original = buildSuspended(
                "trace-uc",
                new SuspendReason.UserConfirmation("shell.exec", "{\"command\":\"rm -rf\"}", "HIGH", "confirm-99"));

        var restored = roundTrip(original);

        assertThat(restored.suspendReason()).isInstanceOf(SuspendReason.UserConfirmation.class);
        var uc = (SuspendReason.UserConfirmation) restored.suspendReason();
        assertThat(uc.toolId()).isEqualTo("shell.exec");
        assertThat(uc.inputJson()).isEqualTo("{\"command\":\"rm -rf\"}");
        assertThat(uc.riskLevel()).isEqualTo("HIGH");
        assertThat(uc.confirmationId()).isEqualTo("confirm-99");
    }

    @Test
    void RemoteDelegation_round_trip_保留具体子类型() throws Exception {
        var original = buildSuspended(
                "trace-rd",
                new SuspendReason.RemoteDelegation("remote-task-1", "https://peer.example/a2a", "翻译稿件"));

        var restored = roundTrip(original);

        assertThat(restored.suspendReason()).isInstanceOf(SuspendReason.RemoteDelegation.class);
        var rd = (SuspendReason.RemoteDelegation) restored.suspendReason();
        assertThat(rd.remoteTaskId()).isEqualTo("remote-task-1");
        assertThat(rd.remoteAgentUrl()).isEqualTo("https://peer.example/a2a");
        assertThat(rd.delegatedGoal()).isEqualTo("翻译稿件");
    }

    @Test
    void ScheduledWakeup_round_trip_保留具体子类型() throws Exception {
        var wakeAt = Instant.parse("2026-05-01T03:30:00Z");
        var original = buildSuspended(
                "trace-sw",
                new SuspendReason.ScheduledWakeup(wakeAt, "等待夜间窗口"));

        var restored = roundTrip(original);

        assertThat(restored.suspendReason()).isInstanceOf(SuspendReason.ScheduledWakeup.class);
        var sw = (SuspendReason.ScheduledWakeup) restored.suspendReason();
        assertThat(sw.wakeupAt()).isEqualTo(wakeAt);
        assertThat(sw.reason()).isEqualTo("等待夜间窗口");
    }

    @Test
    void ExternalDataWait_round_trip_保留具体子类型() throws Exception {
        var original = buildSuspended(
                "trace-edw",
                new SuspendReason.ExternalDataWait("crawler-job-7", "等待新闻爬取完成"));

        var restored = roundTrip(original);

        assertThat(restored.suspendReason()).isInstanceOf(SuspendReason.ExternalDataWait.class);
        var ed = (SuspendReason.ExternalDataWait) restored.suspendReason();
        assertThat(ed.dataSourceId()).isEqualTo("crawler-job-7");
        assertThat(ed.description()).isEqualTo("等待新闻爬取完成");
    }

    @Test
    void InteractionSource_派生属性不污染_stateJson_并能反序列化() throws Exception {
        // CRON 来源会让 isAutonomous 返回 true；如果 Jackson 误把它当成 bean property，
        // 反序列化时会回到 Source record 的构造器，而 record 没有对应字段。
        var request = buildRequest("test:cron-session", InteractionSource.cron("cron:nightly"));
        var state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .traceId("trace-cron")
                .suspended(true)
                .suspendReason(new SuspendReason.ScheduledWakeup(Instant.now(), "夜间"))
                .build();
        var sa = SuspendedAgent.from(state, objectMapper);

        // stateJson 不应包含 "autonomous" 或 "channel" 字段（来自 isAutonomous / isChannel 的派生属性）
        assertThat(sa.stateJson()).doesNotContain("\"autonomous\"");
        assertThat(sa.stateJson()).doesNotContain("\"channel\":true").doesNotContain("\"channel\":false");

        // 完整 round-trip 不抛异常，并能还原 SourceKind
        var restored = sa.toAgentState(objectMapper);
        assertThat(restored.source().sourceKind()).isEqualTo(SourceKind.CRON);
        assertThat(restored.source().sourceId()).isEqualTo("cron:nightly");
        assertThat(restored.source().isAutonomous()).isTrue();
    }

    @Test
    void ReactStep_含_Suspend_嵌套_round_trip() throws Exception {
        var suspendedAt = Instant.parse("2026-04-25T11:00:00Z");
        var stepReason = new SuspendReason.BrowserTakeover("nested-session", "二次验证", suspendedAt, 120);
        var suspendStep = new ReactStep.Suspend(stepReason, suspendedAt, 3);
        var thoughtStep = new ReactStep.Thought("准备挂起");

        var request = buildRequest("test:react-step", InteractionSource.system("unit"));
        var state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .traceId("trace-rs")
                .steps(List.of(thoughtStep, suspendStep))
                .stepCount(2)
                .suspended(true)
                .suspendReason(stepReason)
                .build();

        var sa = SuspendedAgent.from(state, objectMapper);
        var restored = sa.toAgentState(objectMapper);

        assertThat(restored.steps()).hasSize(2);
        assertThat(restored.steps().get(0)).isInstanceOf(ReactStep.Thought.class);
        assertThat(restored.steps().get(1)).isInstanceOf(ReactStep.Suspend.class);
        var rs = (ReactStep.Suspend) restored.steps().get(1);
        assertThat(rs.reason()).isInstanceOf(SuspendReason.BrowserTakeover.class);
        assertThat(((SuspendReason.BrowserTakeover) rs.reason()).sessionId()).isEqualTo("nested-session");
        assertThat(rs.suspendedAt()).isEqualTo(suspendedAt);
        assertThat(rs.stepIndexBeforeSuspend()).isEqualTo(3);
    }

    @Test
    void ReactStep_含_Resume_嵌套_ResumePayload_round_trip() throws Exception {
        var resumedAt = Instant.parse("2026-04-25T12:00:00Z");
        var payload = new ResumePayload.BrowserTakeoverCompleted("nested-session", "已完成");
        var resumeStep = new ReactStep.Resume(payload, resumedAt, Duration.ofSeconds(45));

        var request = buildRequest("test:react-resume", InteractionSource.system("unit"));
        var state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .traceId("trace-resume")
                .steps(List.of(resumeStep))
                .stepCount(1)
                .build();

        var sa = SuspendedAgent.from(state, objectMapper);
        var restored = sa.toAgentState(objectMapper);

        assertThat(restored.steps()).hasSize(1);
        assertThat(restored.steps().getFirst()).isInstanceOf(ReactStep.Resume.class);
        var rr = (ReactStep.Resume) restored.steps().getFirst();
        assertThat(rr.payload()).isInstanceOf(ResumePayload.BrowserTakeoverCompleted.class);
        var btc = (ResumePayload.BrowserTakeoverCompleted) rr.payload();
        assertThat(btc.sessionId()).isEqualTo("nested-session");
        assertThat(btc.note()).isEqualTo("已完成");
        assertThat(rr.resumedAt()).isEqualTo(resumedAt);
        assertThat(rr.suspendDuration()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void ResumePayload_全部_6_种_round_trip_保留具体子类型() throws Exception {
        // sealed interface 类型变量上 round-trip — 检验 @JsonTypeInfo 标签生效
        ResumePayload[] originals = new ResumePayload[]{
                new ResumePayload.WorkflowResult("exec-1", "DONE", "{\"ok\":true}"),
                new ResumePayload.UserDecision("conf-1", true, "确认"),
                new ResumePayload.RemoteResult("remote-1", "{\"r\":1}"),
                new ResumePayload.WakeupSignal(Instant.parse("2026-05-01T03:30:00Z")),
                new ResumePayload.DataReady("ds-1", "/tmp/data.csv"),
                new ResumePayload.BrowserTakeoverCompleted("s-1", "ok")
        };

        for (ResumePayload original : originals) {
            String json = objectMapper.writeValueAsString(original);
            ResumePayload restored = objectMapper.readValue(json, ResumePayload.class);
            assertThat(restored).isInstanceOf(original.getClass());
        }
    }

    // ─── 辅助方法 ───

    /** 构造最小可序列化请求。 */
    private AgentRequest buildRequest(String sessionId, InteractionSource source) {
        return new AgentRequest(
                "测试目标", sessionId, source, "user-1", "turn-1",
                null, AgentTaskMode.AUTO, null, null, null,
                0, null, null, null, null, null, null);
    }

    /** 构造一个携带指定 SuspendReason 的挂起快照（state 只挂在顶层 reason 字段）。 */
    private SuspendedAgent buildSuspended(String traceId, SuspendReason reason) {
        var request = buildRequest("test:" + traceId, InteractionSource.system("unit"));
        var state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .traceId(traceId)
                .suspended(true)
                .suspendReason(reason)
                .build();
        return SuspendedAgent.from(state, objectMapper);
    }

    /** 写入 stateJson 后立即反序列化回 ReactAgentState。 */
    private ReactAgentState roundTrip(SuspendedAgent original) {
        return original.toAgentState(objectMapper);
    }
}
