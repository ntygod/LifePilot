package com.lifepilot.agent;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.agent.trace.TraceRecorder;
import com.lifepilot.agent.trace.TraceStep;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Agent 核心控制循环。
 *
 * <p>协调 LLM 调用、状态转换、预算检查、轨迹记录，
 * 循环执行直到终止条件满足。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int MAX_LOOP_ITERATIONS = 50;
    private static final int MAX_CONSECUTIVE_BLOCKS = 3;

    private final StateReducer stateReducer;
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final TraceRecorder traceRecorder;
    private final SessionManager sessionManager;
    private final ActionParser actionParser;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;

    public AgentLoop(StateReducer stateReducer,
                     ContextAssembler contextAssembler,
                     LlmRouter llmRouter,
                     TraceRecorder traceRecorder,
                     SessionManager sessionManager,
                     ActionParser actionParser,
                     AgentToolProvider agentToolProvider,
                     AgentConfigProperties config) {
        this.stateReducer = stateReducer;
        this.contextAssembler = contextAssembler;
        this.llmRouter = llmRouter;
        this.traceRecorder = traceRecorder;
        this.sessionManager = sessionManager;
        this.actionParser = actionParser;
        this.agentToolProvider = agentToolProvider;
        this.config = config;
    }

    /**
     * 执行 Agent 循环。
     *
     * @param request 用户请求
     * @return Agent 响应
     */
    public AgentResponse run(AgentRequest request) {
        AgentState state;
        try {
            // 初始化状态
            var existingSession = sessionManager.findSession(request.sessionId());
            state = existingSession.isPresent()
                    ? AgentState.fromSession(existingSession.get(), request)
                    : AgentState.init(request);

            // 开始轨迹记录
            var traceContext = traceRecorder.startTrace(
                    state.traceId(), state.sessionId(), request.message());

            Instant startTime = Instant.now();
            int consecutiveBlocks = 0;

            // 核心循环
            for (int iteration = 0; !state.isDone(); iteration++) {
                // 硬限制检查
                if (iteration >= MAX_LOOP_ITERATIONS) {
                    var action = new Action.BudgetExhausted("循环次数达到硬限制: " + MAX_LOOP_ITERATIONS);
                    state = reduceAndRecord(state, action, traceContext, startTime);
                    break;
                }

                // 更新已用时间
                Duration elapsed = Duration.between(startTime, Instant.now());
                state = state.toBuilder().budget(state.budget().withElapsed(elapsed)).build();

                // 预算检查
                if (state.budget().exceeded()) {
                    var action = new Action.BudgetExhausted(state.budget().exceedReason());
                    state = reduceAndRecord(state, action, traceContext, startTime);
                    break;
                }

                // 上下文组装
                var assembledContext = contextAssembler.assemble(state);

                // LLM 调用
                Action action;
                try {
                    String scene = mapPhaseToScene(state.phase());
                    var response = llmRouter.call(scene, assembledContext.userPrompt(), null);
                    action = actionParser.parse(state.phase(), response.content());
                } catch (LlmUnavailableException e) {
                    log.warn("LLM 不可用: error={}", e.getMessage());
                    action = new Action.BudgetExhausted("LLM 不可用: " + e.getMessage());
                }

                // 状态转换 + 轨迹记录
                state = reduceAndRecord(state, action, traceContext, startTime);

                // 连续护栏阻断检查
                if (action instanceof Action.Blocked) {
                    consecutiveBlocks++;
                    if (consecutiveBlocks >= MAX_CONSECUTIVE_BLOCKS) {
                        log.warn("连续护栏阻断达到上限: count={}", consecutiveBlocks);
                        var forceTerminate = new Action.BudgetExhausted(
                                "连续护栏阻断达到上限: " + MAX_CONSECUTIVE_BLOCKS);
                        state = reduceAndRecord(state, forceTerminate, traceContext, startTime);
                        break;
                    }
                } else {
                    consecutiveBlocks = 0;
                }
            }

            // 完成轨迹记录
            traceRecorder.endTrace(traceContext, state.finalOutput(),
                    state.terminationReason() == null, null, state.terminationReason());

            // 异步后处理（Virtual Thread）
            asyncPostProcess(state);

            return state.toResponse();

        } catch (Exception e) {
            log.error("Agent 循环异常终止: error={}", e.getMessage(), e);
            // 构建错误状态用于响应
            var errorState = AgentState.init(request);
            return AgentResponse.error(errorState, e);
        }
    }

    /** 执行状态转换并记录轨迹步骤。 */
    private AgentState reduceAndRecord(AgentState state, Action action,
                                        TraceRecorder.TraceContext traceContext,
                                        Instant startTime) {
        AgentPhase phaseBefore = state.phase();
        AgentState newState = stateReducer.reduce(state, action);

        var traceStep = TraceStep.builder()
                .traceId(state.traceId())
                .stepIndex(newState.stepCount() - 1)
                .phaseBefore(phaseBefore)
                .phaseAfter(newState.phase())
                .action(action)
                .toolId(extractToolId(action))
                .blocked(action instanceof Action.Blocked)
                .blockReason(action instanceof Action.Blocked b ? b.reason() : null)
                .tokensUsed(extractTokensUsed(action))
                .latencyMs(Duration.between(startTime, Instant.now()).toMillis())
                .timestamp(Instant.now())
                .build();

        traceRecorder.recordStep(traceContext, traceStep);
        return newState;
    }

    /** 根据 AgentPhase 映射 LLM 场景。 */
    private String mapPhaseToScene(AgentPhase phase) {
        var mapping = config.getLoop().getSceneMapping();
        String key = phase.name().toLowerCase();
        return mapping.getOrDefault(key, LlmScene.AGENT_REASONING);
    }

    /** 从 Action 中提取 toolId。 */
    private String extractToolId(Action action) {
        return switch (action) {
            case Action.ToolResult a -> a.toolId();
            case Action.Blocked a -> a.toolId();
            case Action.SubAgentResult a -> a.skillId();
            default -> null;
        };
    }

    /** 从 Action 中提取 tokensUsed。 */
    private int extractTokensUsed(Action action) {
        return switch (action) {
            case Action.ToolResult a -> a.tokensUsed();
            case Action.SubAgentResult a -> a.tokensUsed();
            default -> 0;
        };
    }

    /** 异步后处理：会话持久化 + 轨迹持久化。 */
    private void asyncPostProcess(AgentState finalState) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> {
            try {
                sessionManager.saveSession(finalState);
            } catch (Exception e) {
                log.warn("会话持久化失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
        });
        executor.submit(() -> {
            try {
                traceRecorder.persistTrace(finalState.traceId());
            } catch (Exception e) {
                log.warn("轨迹持久化失败: traceId={}, error={}",
                        finalState.traceId(), e.getMessage());
            }
        });
        executor.close();
    }
}
