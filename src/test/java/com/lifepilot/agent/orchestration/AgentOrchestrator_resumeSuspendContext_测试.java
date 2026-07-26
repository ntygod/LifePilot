package com.lifepilot.agent.orchestration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.ResumePolicy;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator 挂起恢复上下文测试。
 *
 * @author zsg
 * @since 2026-07-07
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestrator_resumeSuspendContext_测试 {

    @Mock private ReactAgentLoop agentLoop;
    @Mock private AgentPersistenceHandler persistenceHandler;
    @Mock private StreamingEventHandler streamingEventHandler;
    @Mock private GenerationRouter generationRouter;
    @Mock private SuspendStore suspendStore;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AgentConfigProperties config = new AgentConfigProperties();

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void 构造编排器() {
        orchestrator = new AgentOrchestrator(
                agentLoop, persistenceHandler, streamingEventHandler, config, objectMapper, generationRouter,
                null, null, null, null, null, suspendStore, null, null, null);
    }

    @Test
    void RESUME从SuspendStore恢复未带最新上下文时应保留已保存恢复上下文() {
        String sessionId = "test:suspend-context";
        Map<String, Object> savedRecoveryContext = Map.of(
                "action", "RESUME",
                "sourceTraceId", "trace-suspended",
                "checkpoint", Map.of(
                        "kind", "TOOL_FAILURE",
                        "toolId", "file.write",
                        "artifactRefs", List.of(Map.of(
                                "artifactId", "artifact-report",
                                "fileName", "报告.md",
                                "kind", "FILE"))));
        ReactAgentState suspendedState = ReactAgentState.init(
                        new AgentRequest("帮我整理资料并生成报告", sessionId, "web"),
                        Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .turnRecoveryContext(savedRecoveryContext)
                .disabledToolIds(List.of("web", "browser"))
                .suspended(true)
                .suspendReason(new SuspendReason.ExternalDataWait("__await_user_input__", "等待用户补充资料"))
                .build();
        SuspendedAgent suspended = SuspendedAgent.from(suspendedState, objectMapper);
        when(suspendStore.loadAndDeleteBySession(sessionId, "web")).thenReturn(Optional.of(suspended));
        when(agentLoop.coreLoop(any(), any(), any(), any(), any(), any(), any(AgentLoopContext.class)))
                .thenAnswer(invocation -> {
                    ReactAgentState state = invocation.getArgument(0);
                    return state.toBuilder()
                            .done(true)
                            .finalOutput("已继续处理")
                            .completionMode(CompletionMode.NORMAL)
                            .build();
                });

        AgentRequest resumeRequest = new AgentRequest(
                "补充资料：请继续生成报告",
                sessionId,
                InteractionSource.legacy("web", sessionId),
                null,
                "turn-resume-1",
                ChatTurnAction.RESUME,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                ResumePolicy.AUTO,
                null);

        var response = orchestrator.run(resumeRequest);

        ArgumentCaptor<ReactAgentState> stateCaptor = ArgumentCaptor.forClass(ReactAgentState.class);
        verify(suspendStore).loadAndDeleteBySession(sessionId, "web");
        verify(agentLoop).coreLoop(
                stateCaptor.capture(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(AgentLoopContext.class));
        ReactAgentState stateForLoop = stateCaptor.getValue();
        assertThat(stateForLoop.turnRecoveryContext())
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-suspended");
        @SuppressWarnings("unchecked")
        Map<String, Object> checkpoint = (Map<String, Object>) stateForLoop.turnRecoveryContext().get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "file.write")
                .containsKey("artifactRefs");
        assertThat(stateForLoop.resumedFromTraceId()).isEqualTo(suspendedState.traceId());
        assertThat(stateForLoop.turnId()).isEqualTo("turn-resume-1");
        assertThat(stateForLoop.disabledToolIds()).containsExactly("web", "browser");
        assertThat(stateForLoop.steps().getLast())
                .isInstanceOfSatisfying(ReactStep.Observation.class, observation -> {
                    assertThat(observation.toolId()).isEqualTo("user_continue");
                    assertThat(observation.output()).contains("补充资料：请继续生成报告");
                });
        assertThat(response.content()).isEqualTo("已继续处理");
    }
}
