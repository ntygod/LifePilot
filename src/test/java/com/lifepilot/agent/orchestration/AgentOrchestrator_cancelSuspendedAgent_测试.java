package com.lifepilot.agent.orchestration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.ChatTurnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator.cancelSuspendedAgent 单元测试。
 *
 * <p>验证用户取消挂起 Agent 的硬终止主路径：</p>
 * <ul>
 *   <li>挂起记录存在 → 走 loadAndDelete + markCompleted(FAILED) + 不进入 ReAct 循环</li>
 *   <li>挂起记录不存在 → 幂等返回，仅 warn 不抛</li>
 *   <li>SuspendStore 未配置 → 抛 IllegalStateException</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-25
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestrator_cancelSuspendedAgent_测试 {

    @Mock private ReactAgentLoop agentLoop;
    @Mock private AgentPersistenceHandler persistenceHandler;
    @Mock private StreamingEventHandler streamingEventHandler;
    @Mock private GenerationRouter generationRouter;
    @Mock private SuspendStore suspendStore;
    @Mock private ChatTurnService chatTurnService;

    // 与 Spring Boot 默认 ObjectMapper 行为一致：忽略未知字段（如 InteractionSource.isAutonomous() getter）
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AgentConfigProperties config = new AgentConfigProperties();

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void 构造_注入_全量依赖() {
        orchestrator = new AgentOrchestrator(
                agentLoop, persistenceHandler, streamingEventHandler, config, objectMapper, generationRouter,
                null, null, null, null, null, suspendStore, chatTurnService, null, null);
    }

    @Test
    void 挂起记录存在_硬终止_落库为_FAILED_并删除挂起记录() {
        var traceId = "trace-cancel-1";
        var suspended = buildBrowserTakeoverSuspended(traceId, "session-login");
        when(suspendStore.loadAndDelete(traceId)).thenReturn(Optional.of(suspended));

        orchestrator.cancelSuspendedAgent(traceId, "用户取消了本次任务");

        // 关键：loadAndDelete 被调用一次，原子删除挂起记录
        verify(suspendStore).loadAndDelete(traceId);
        verify(suspendStore, never()).save(any());

        // turn 必须落为 FAILED（无 CANCELLED 枚举时的近似）
        var statusCaptor = ArgumentCaptor.forClass(ChatTurnStatus.class);
        verify(chatTurnService).markCompleted(
                eq(suspended.sessionId()),
                any(),
                statusCaptor.capture(),
                any(),
                any(),
                any(),
                any());
        assertThat(statusCaptor.getValue()).isEqualTo(ChatTurnStatus.FAILED);
    }

    @Test
    void 挂起记录不存在_幂等返回_不抛异常_不落库() {
        var traceId = "trace-missing";
        when(suspendStore.loadAndDelete(traceId)).thenReturn(Optional.empty());

        // 不抛
        orchestrator.cancelSuspendedAgent(traceId, "重复点击放弃");

        verify(suspendStore).loadAndDelete(traceId);
        verify(chatTurnService, never()).markCompleted(any(), any(), any(), any(), any(), any(), any());
        verify(chatTurnService, never()).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    void SuspendStore_未配置_抛_IllegalStateException() {
        var orchestratorWithoutStore = new AgentOrchestrator(
                agentLoop, persistenceHandler, streamingEventHandler, config, objectMapper, generationRouter,
                null, null, null, null, null, null, chatTurnService, null, null);

        assertThatThrownBy(() ->
                orchestratorWithoutStore.cancelSuspendedAgent("any-trace", "原因"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SuspendStore");
    }

    /**
     * 构造一个浏览器接管挂起记录。
     *
     * <p>{@code stateJson} 直接写最小字段集，绕开 {@link SuspendedAgent#from} 全字段序列化路径
     * （{@link com.lifepilot.interaction.model.InteractionSource} 含 {@code isAutonomous()}
     * 派生字段、{@link SuspendReason} 是 sealed interface，二者都需要专门的 ObjectMapper
     * 配置才能 round-trip）。生产路径下 {@link com.lifepilot.agent.suspend.store.SqliteSuspendStore}
     * 把 reason 单独存到 reason_json 列，所以本测试在 stateJson 里不放 suspendReason，
     * 仅依赖 {@link SuspendedAgent#suspendReason()} 字段。</p>
     */
    private SuspendedAgent buildBrowserTakeoverSuspended(String traceId, String browserSessionId) {
        String sessionId = "test:cancel-session";
        // 最小可解析的 ReactAgentState JSON：只放 toAgentState() 后续读取的关键字段
        String stateJson = """
                {
                  "traceId": "%s",
                  "sessionId": "%s",
                  "turnId": "turn-1",
                  "userId": "user-1",
                  "channel": "web",
                  "goal": "登录任务",
                  "source": {"sourceKind": "SYSTEM", "sourceId": "unit-test"},
                  "stepCount": 0,
                  "steps": [],
                  "shortTermMemory": [],
                  "mentionedEntities": [],
                  "depth": 0,
                  "done": false,
                  "completionMode": "NORMAL",
                  "earlyStopRejectCount": 0,
                  "suspended": true
                }
                """.formatted(traceId, sessionId);
        return SuspendedAgent.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .channel("web")
                .suspendReason(new SuspendReason.BrowserTakeover(
                        browserSessionId, "需要登录", Instant.now(), 300))
                .stateJson(stateJson)
                .suspendedAt(Instant.now())
                .build();
    }
}
