package com.lifepilot.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.persistence.AgentPersistenceHandler;
import com.lifepilot.agent.streaming.StreamingEventHandler;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidationException;
import com.lifepilot.media.MediaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator 单元测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestrator_单元测试 {

    @Mock
    private ReactAgentLoop agentLoop;

    @Mock
    private AgentPersistenceHandler persistenceHandler;

    @Mock
    private StreamingEventHandler streamingEventHandler;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private MultimodalRouter multimodalRouter;

    @Mock
    private MediaValidator mediaValidator;

    @Mock
    private MediaProcessor mediaProcessor;

    @Mock
    private ChatTurnService chatTurnService;

    @Mock
    private SseSessionManager sseSessionManager;

    @Mock
    private SuspendStore suspendStore;

    private AgentConfigProperties config;
    private AgentOrchestrator orchestrator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        config = new AgentConfigProperties();
        objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        orchestrator = new AgentOrchestrator(
                agentLoop,
                persistenceHandler,
                streamingEventHandler,
                config,
                objectMapper,
                generationRouter,
                null,
                multimodalRouter,
                mediaValidator,
                mediaProcessor,
                null,
                suspendStore,
                chatTurnService,
                null  // workspaceService
        );
    }

    @Test
    void 流式媒体校验失败时应回写Turn失败状态() {
        var request = buildMediaRequest("session-media-failed", "turn-media-failed");
        doThrow(new MediaValidationException("文件过大")).when(mediaValidator).validateAll(any());

        orchestrator.runStreaming(request, "stream-1", sseSessionManager, new CancellationToken());

        verify(chatTurnService).markFailed(
                eq("session-media-failed"),
                eq("turn-media-failed"),
                anyString(),
                eq(500),
                eq("文件过大")
        );
        verify(streamingEventHandler).sendStreamError(
                eq(sseSessionManager),
                eq("stream-1"),
                eq(400),
                eq("媒体内容校验失败：文件过大"),
                anyString(),
                eq("turn-media-failed"),
                eq(ChatTurnStatus.FAILED)
        );
        verify(agentLoop, never()).coreLoop(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 仅有Progress步骤时后置异常不应被降级() {
        var request = buildTextRequest("session-progress-only", "turn-progress-only");
        ReactAgentState progressOnlyState = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .appendStep(new ReactStep.Progress("正在检索相关记忆和知识…"))
                .toBuilder()
                .done(true)
                .build();

        when(agentLoop.coreLoop(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(progressOnlyState);
        when(persistenceHandler.persistUserMessageReturningId(any(), any())).thenReturn("user-entry-1");
        doThrow(new IllegalStateException("存储失败")).when(chatTurnService).markCompleted(
                eq("session-progress-only"),
                eq("turn-progress-only"),
                any(),
                nullable(String.class),
                eq(progressOnlyState.traceId()),
                nullable(String.class),
                any()
        );

        AgentResponse response = orchestrator.run(request);

        assertThat(response.turnStatus()).isEqualTo(ChatTurnStatus.FAILED);
        assertThat(response.completionMode()).isEqualTo(CompletionMode.NORMAL);
        assertThat(response.completionReason()).isEqualTo(CompletionReason.UNEXPECTED_EXCEPTION);
        assertThat(response.content()).contains("存储失败");
        verify(chatTurnService).markFailed(
                eq("session-progress-only"),
                eq("turn-progress-only"),
                eq(progressOnlyState.traceId()),
                eq(500),
                eq("存储失败")
        );
    }

    @Test
    void 恢复执行完成前不应提前删除挂起快照() throws InterruptedException {
        ReactAgentState suspendedState = buildSuspendedState("trace-resume-complete", "session-resume-complete", "turn-resume-complete");
        SuspendedAgent snapshot = mockSuspendedAgent(suspendedState);
        CountDownLatch loopStarted = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);

        when(suspendStore.load("trace-resume-complete")).thenReturn(Optional.of(snapshot));
        when(agentLoop.coreLoop(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ReactAgentState resumed = invocation.getArgument(0);
                    loopStarted.countDown();
                    assertThat(allowFinish.await(1, TimeUnit.SECONDS)).isTrue();
                    return resumed.toBuilder()
                            .done(true)
                            .finalOutput("恢复执行完成")
                            .completionReason(CompletionReason.DIRECT_ANSWER)
                            .build();
                });
        when(persistenceHandler.persistAssistantMessage(any(), any())).thenReturn("assistant-resume-1");

        orchestrator.resumeFromSuspend("trace-resume-complete",
                new ResumePayload.DataReady("__await_user_input__", "补充信息已提供"));

        assertThat(loopStarted.await(1, TimeUnit.SECONDS)).isTrue();
        verify(suspendStore, never()).delete("trace-resume-complete");

        allowFinish.countDown();

        verify(suspendStore, timeout(1000)).delete("trace-resume-complete");
    }

    @Test
    void 恢复后再次挂起应重新保存挂起快照() {
        ReactAgentState suspendedState = buildSuspendedState("trace-resume-suspend", "session-resume-suspend", "turn-resume-suspend");
        SuspendedAgent snapshot = mockSuspendedAgent(suspendedState);

        when(suspendStore.load("trace-resume-suspend")).thenReturn(Optional.of(snapshot));
        when(agentLoop.coreLoop(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    ReactAgentState resumed = invocation.getArgument(0);
                    return resumed.suspend(new com.lifepilot.agent.model.SuspendReason.ExternalDataWait(
                            "__await_user_input__",
                            "仍需额外信息"
                    ));
                });

        orchestrator.resumeFromSuspend("trace-resume-suspend",
                new ResumePayload.DataReady("__await_user_input__", "第一次补充信息"));

        verify(suspendStore, timeout(1000).atLeastOnce()).save(any());
        verify(suspendStore, never()).delete("trace-resume-suspend");
    }

    private AgentRequest buildTextRequest(String sessionId, String turnId) {
        return new AgentRequest(
                "请给我一个简短总结",
                sessionId,
                "web",
                null,
                turnId,
                ChatTurnAction.SEND,
                null,
                Budget.fromConfig(config.getBudget()),
                null,
                0,
                null,
                null,
                null,
                null,
                null
        );
    }

    private ReactAgentState buildSuspendedState(String traceId, String sessionId, String turnId) {
        AgentRequest request = buildTextRequest(sessionId, turnId);
        return ReactAgentState.init(request, Budget.fromConfig(config.getBudget()))
                .toBuilder()
                .traceId(traceId)
                .build()
                .suspend(new com.lifepilot.agent.model.SuspendReason.ExternalDataWait(
                        "__await_user_input__",
                        "请补充信息"
                ));
    }

    private SuspendedAgent mockSuspendedAgent(ReactAgentState state) {
        SuspendedAgent snapshot = org.mockito.Mockito.mock(SuspendedAgent.class);
        when(snapshot.suspendReason()).thenReturn(state.suspendReason());
        when(snapshot.toAgentState(objectMapper)).thenReturn(state);
        when(snapshot.suspendedAt()).thenReturn(Instant.now().minusSeconds(1));
        return snapshot;
    }

    private AgentRequest buildMediaRequest(String sessionId, String turnId) {
        var media = new MediaContent(
                "img-1",
                "image/png",
                new byte[]{1, 2, 3},
                "demo.png",
                3,
                Map.of("source", "test")
        );
        return new AgentRequest(
                "请识别图片内容",
                sessionId,
                "web",
                null,
                turnId,
                ChatTurnAction.SEND,
                null,
                Budget.fromConfig(config.getBudget()),
                null,
                0,
                null,
                null,
                List.of(media),
                null,
                null
        );
    }
}
