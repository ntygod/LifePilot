package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentLoopExecutingPlanTest {

    @Test
    void executing_按计划执行工具_成功时返回ToolResult并根据步骤数设置hasMore() throws Exception {
        AgentLoop loop = new AgentLoop(
                new StateReducer(),
                mock(ContextAssembler.class),
                mock(LlmRouter.class),
                mock(MultimodalRouter.class),
                mock(TraceRecorder.class),
                new ObjectMapper(),
                mock(SessionManager.class),
                null,
                mock(ActionParser.class),
                mock(AgentToolProvider.class),
                new AgentConfigProperties(),
                null, null, null, null, null,
                mock(PromptRegistry.class),
                null,
                null,
                null
        );

        ToolCallback okTool = toolCallback("calendar_create_event", "{\"ok\":true}");

        ExecutionPlan plan = new ExecutionPlan(List.of(
                new PlanStep(0, "calendar_create_event", Map.of("title", "与赵总会议"), List.of(), "创建事件"),
                new PlanStep(1, "calendar_create_event", Map.of("title", "后续"), List.of(), "创建事件2")
        ), 100, "rationale");

        AgentState state = AgentState.builder()
                .traceId("t")
                .sessionId("s")
                .goal("g")
                .phase(AgentPhase.EXECUTING)
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .plan(plan)
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

        Action action = invokeExecuteNextPlannedToolStep(loop, state, List.of(okTool));

        assertThat(action).isInstanceOf(Action.ToolResult.class);
        Action.ToolResult tr = (Action.ToolResult) action;
        assertThat(tr.toolId()).isEqualTo("calendar_create_event");
        assertThat(tr.success()).isTrue();
        assertThat(tr.hasMore()).isTrue();
    }

    @Test
    void executing_工具输出包含error字段_视为失败并强制hasMore为false() throws Exception {
        AgentLoop loop = new AgentLoop(
                new StateReducer(),
                mock(ContextAssembler.class),
                mock(LlmRouter.class),
                mock(MultimodalRouter.class),
                mock(TraceRecorder.class),
                new ObjectMapper(),
                mock(SessionManager.class),
                null,
                mock(ActionParser.class),
                mock(AgentToolProvider.class),
                new AgentConfigProperties(),
                null, null, null, null, null,
                mock(PromptRegistry.class),
                null,
                null,
                null
        );

        ToolCallback badTool = toolCallback("calendar_create_event", "{\"error\":\"boom\"}");

        ExecutionPlan plan = new ExecutionPlan(List.of(
                new PlanStep(0, "calendar_create_event", Map.of(), List.of(), "创建事件"),
                new PlanStep(1, "calendar_create_event", Map.of(), List.of(), "创建事件2")
        ), 100, "rationale");

        AgentState state = AgentState.builder()
                .traceId("t")
                .sessionId("s")
                .goal("g")
                .phase(AgentPhase.EXECUTING)
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .plan(plan)
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

        Action action = invokeExecuteNextPlannedToolStep(loop, state, List.of(badTool));

        assertThat(action).isInstanceOf(Action.ToolResult.class);
        Action.ToolResult tr = (Action.ToolResult) action;
        assertThat(tr.success()).isFalse();
        assertThat(tr.hasMore()).isFalse();
    }

    @Test
    void executing_计划引用不存在的工具_返回Blocked并回退UNDERSTANDING() throws Exception {
        AgentLoop loop = new AgentLoop(
                new StateReducer(),
                mock(ContextAssembler.class),
                mock(LlmRouter.class),
                mock(MultimodalRouter.class),
                mock(TraceRecorder.class),
                new ObjectMapper(),
                mock(SessionManager.class),
                null,
                mock(ActionParser.class),
                mock(AgentToolProvider.class),
                new AgentConfigProperties(),
                null, null, null, null, null,
                mock(PromptRegistry.class),
                null,
                null,
                null
        );

        ExecutionPlan plan = new ExecutionPlan(List.of(
                new PlanStep(0, "calendar_create_event", Map.of(), List.of(), "创建事件")
        ), 100, "rationale");

        AgentState state = AgentState.builder()
                .traceId("t")
                .sessionId("s")
                .goal("g")
                .phase(AgentPhase.EXECUTING)
                .channel("web")
                .steps(List.of())
                .stepCount(0)
                .plan(plan)
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

        Action action = invokeExecuteNextPlannedToolStep(loop, state, List.of());

        assertThat(action).isInstanceOf(Action.Blocked.class);
        Action.Blocked blocked = (Action.Blocked) action;
        assertThat(blocked.toolId()).isEqualTo("calendar_create_event");
        assertThat(blocked.riskLevel()).isNotNull();
    }

    private static ToolCallback toolCallback(@NonNull String name, @NonNull String output) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(Objects.requireNonNull(name, "name"))
                .description("test tool")
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override
            @NonNull
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput) {
                return Objects.requireNonNull(output, "output");
            }
        };
    }

    private static Action invokeExecuteNextPlannedToolStep(AgentLoop loop,
                                                           AgentState state,
                                                           List<ToolCallback> callbacks) throws Exception {
        Method m = AgentLoop.class.getDeclaredMethod("executeNextPlannedToolStep", AgentState.class, List.class);
        m.setAccessible(true);
        return (Action) m.invoke(loop, state, callbacks);
    }
}

