package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.TokenBudget;
import com.lifepilot.agent.model.Action;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ExecutionPlan;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopStreamingFailureTest {

    @Test
    void streaming_llmUnavailable_shouldPersistSystemMessage_andReturnErrorRecovery() throws Exception {
        LlmRouter llmRouter = mock(LlmRouter.class);
        MultimodalRouter multimodalRouter = mock(MultimodalRouter.class);
        TraceRecorder traceRecorder = mock(TraceRecorder.class);
        SessionManager sessionManager = mock(SessionManager.class);
        ActionParser actionParser = mock(ActionParser.class);
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        WorkingMemory workingMemory = mock(WorkingMemory.class);
        ConversationHistoryStore conversationHistoryStore = mock(ConversationHistoryStore.class);

        AgentLoop loop = new AgentLoop(
                new StateReducer(),
                mock(com.lifepilot.agent.context.ContextAssembler.class),
                llmRouter,
                multimodalRouter,
                traceRecorder,
                new ObjectMapper(),
                sessionManager,
                null,
                actionParser,
                agentToolProvider,
                new AgentConfigProperties(),
                workingMemory,
                conversationHistoryStore,
                null,
                null,
                null,
                mock(PromptRegistry.class),
                null,
                null
        );

        AgentRequest request = new AgentRequest("hi", "s1", "web", null, null, null,
                0, null, null, List.of());
        AgentState state = AgentState.builder()
                .traceId("t1")
                .sessionId("s1")
                .goal("hi")
                .phase(AgentPhase.RESPONDING)
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .plan(new ExecutionPlan(List.of(), 0, ""))
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.defaultBudget())
                .parentTraceId(null)
                .depth(0)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();

        AssembledContext ctx = new AssembledContext(
                "sys",
                "user",
                List.of(),
                TokenBudget.allocate(AgentPhase.RESPONDING, 1000),
                0,
                0.0f,
                0,
                false,
                List.of()
        );

        // 流式路由直接抛异常，触发 ErrorRecovery + system message persistence
        when(llmRouter.streamWithInfo(anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        TraceContext traceContext = new TraceContext("t1", "s1", "hi");

        Action action = invokeCallLlmStreamingAndParseAction(loop, request, state, ctx, traceContext);

        assertThat(action).isInstanceOf(Action.ErrorRecovery.class);
        Action.ErrorRecovery er = (Action.ErrorRecovery) action;
        assertThat(er.errorMessage()).contains("流式 LLM 调用失败");

        verify(conversationHistoryStore).appendSystemMessage(eq("s1"), contains("模型服务暂时不可用"), eq("t1"));
        verify(workingMemory).append(eq("s1"), org.mockito.ArgumentMatchers.any());
        // trace step should be recorded (best-effort)
        verify(traceRecorder).recordStep(eq(traceContext), org.mockito.ArgumentMatchers.any());
    }

    private static Action invokeCallLlmStreamingAndParseAction(AgentLoop loop,
                                                               AgentRequest request,
                                                               AgentState state,
                                                               AssembledContext assembledContext,
                                                               TraceContext traceContext) throws Exception {
        Method m = AgentLoop.class.getDeclaredMethod(
                "callLlmStreamingAndParseAction",
                AgentRequest.class,
                AgentState.class,
                AssembledContext.class,
                TraceContext.class,
                String.class,
                SseSessionManager.class,
                String.class
        );
        m.setAccessible(true);
        return (Action) m.invoke(loop, request, state, assembledContext, traceContext,
                "stream-1", mock(SseSessionManager.class), "turn-1");
    }
}

