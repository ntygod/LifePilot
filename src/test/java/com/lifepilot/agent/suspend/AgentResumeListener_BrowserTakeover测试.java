package com.lifepilot.agent.suspend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.suspend.event.BrowserTakeoverCompletedEvent;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.interaction.model.InteractionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentResumeListener 浏览器接管事件分流测试。
 *
 * <p>验证 cancelled 标志位决定走 {@code cancelSuspendedAgent}（硬终止主路径）
 * 还是 {@code resumeFromSuspend}（继续推理路径），不再共用前缀短路。</p>
 *
 * @author zsg
 * @since 2026-04-25
 */
@ExtendWith(MockitoExtension.class)
class AgentResumeListener_BrowserTakeover测试 {

    @Mock private AgentOrchestrator orchestrator;
    @Mock private SuspendStore suspendStore;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentConfigProperties config = new AgentConfigProperties();

    private AgentResumeListener listener;

    @BeforeEach
    void 构造监听器() {
        listener = new AgentResumeListener(orchestrator, suspendStore);
    }

    @Test
    void 用户取消_走_cancelSuspendedAgent_主路径_不走_resume() {
        String browserSessionId = "browser-login-1";
        var suspended = buildSuspended("trace-1", browserSessionId);
        when(suspendStore.findByReasonType("BrowserTakeover")).thenReturn(List.of(suspended));

        listener.onBrowserTakeoverCompleted(
                new BrowserTakeoverCompletedEvent(browserSessionId, true, "登录失败放弃"));

        var reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(orchestrator).cancelSuspendedAgent(eq("trace-1"), reasonCaptor.capture());
        // reason 应包含原始备注，便于审计
        assertThat(reasonCaptor.getValue()).contains("登录失败放弃");
        verify(orchestrator, never()).resumeFromSuspend(any(), any());
    }

    @Test
    void 正常完成_走_resumeFromSuspend_继续推理() {
        String browserSessionId = "browser-2fa-1";
        var suspended = buildSuspended("trace-2", browserSessionId);
        when(suspendStore.findByReasonType("BrowserTakeover")).thenReturn(List.of(suspended));

        listener.onBrowserTakeoverCompleted(
                new BrowserTakeoverCompletedEvent(browserSessionId, false, "已完成验证码输入"));

        var payloadCaptor = ArgumentCaptor.forClass(ResumePayload.class);
        verify(orchestrator).resumeFromSuspend(eq("trace-2"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .isInstanceOf(ResumePayload.BrowserTakeoverCompleted.class);
        var payload = (ResumePayload.BrowserTakeoverCompleted) payloadCaptor.getValue();
        // 正常完成不带 USER_CANCELLED 前缀
        assertThat(payload.isUserCancelled()).isFalse();
        assertThat(payload.note()).isEqualTo("已完成验证码输入");

        verify(orchestrator, never()).cancelSuspendedAgent(any(), any());
    }

    @Test
    void 没有匹配的挂起记录_两条路径都不调用() {
        when(suspendStore.findByReasonType("BrowserTakeover")).thenReturn(List.of());

        listener.onBrowserTakeoverCompleted(
                new BrowserTakeoverCompletedEvent("browser-not-exist", true, null));

        verify(orchestrator, never()).cancelSuspendedAgent(any(), any());
        verify(orchestrator, never()).resumeFromSuspend(any(), any());
    }

    private SuspendedAgent buildSuspended(String traceId, String browserSessionId) {
        var request = new AgentRequest(
                "登录",
                "test:resume-listener",
                InteractionSource.system("unit-test"),
                "user-1",
                "turn-1",
                null,
                AgentTaskMode.AUTO,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null);
        var state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .traceId(traceId)
                .suspended(true)
                .suspendReason(new SuspendReason.BrowserTakeover(
                        browserSessionId, "需要 2FA", Instant.now(), 300))
                .build();
        return SuspendedAgent.from(state, objectMapper);
    }
}
