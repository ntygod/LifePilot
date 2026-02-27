package com.lifepilot.agent;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
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

    private final StateReducer stateReducer;
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final TraceRecorder traceRecorder;
    private final SessionManager sessionManager;
    private final ActionParser actionParser;
    // 预留：工具回调将在 LLM 调用集成 Function Calling 时使用
    @SuppressWarnings("unused")
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
            int maxIterations = config.getLoop().getMaxIterations();
            int maxConsecutiveBlocks = config.getLoop().getMaxConsecutiveBlocks();

            // 核心循环
            for (int iteration = 0; !state.isDone(); iteration++) {
                // 硬限制检查
                if (iteration >= maxIterations) {
                    var action = new Action.BudgetExhausted("循环次数达到硬限制: " + maxIterations);
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

                // SubAgent 场景：注入 Agent 专属 System Prompt
                if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                    String mergedSystemPrompt = request.systemPrompt() + "\n\n" + assembledContext.systemPrompt();
                    assembledContext = new com.lifepilot.agent.context.AssembledContext(
                            mergedSystemPrompt,
                            assembledContext.userPrompt(),
                            assembledContext.retrievedMemories(),
                            assembledContext.tokenBudget(),
                            assembledContext.retrievalCount(),
                            assembledContext.topRetrievalScore(),
                            assembledContext.workingMemoryTokens(),
                            assembledContext.degraded()
                    );
                }

                // LLM 调用
                Action action;
                try {
                    // SubAgent 场景：使用偏好 Provider 作为场景名
                    String scene = request.preferredProvider() != null
                            ? request.preferredProvider()
                            : mapPhaseToScene(state.phase());
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
                    if (consecutiveBlocks >= maxConsecutiveBlocks) {
                        log.warn("连续护栏阻断达到上限: count={}", consecutiveBlocks);
                        var forceTerminate = new Action.BudgetExhausted(
                                "连续护栏阻断达到上限: " + maxConsecutiveBlocks);
                        state = reduceAndRecord(state, forceTerminate, traceContext, startTime);
                        break;
                    }
                } else {
                    consecutiveBlocks = 0;
                }
            }

            // 완成轨迹记录
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
                                        TraceContext traceContext,
                                        Instant startTime) {
        AgentPhase phaseBefore = state.phase();
        AgentState newState = stateReducer.reduce(state, action);

        var step = new StateTransitionStep(
                newState.stepCount() - 1,
                Instant.now(),
                Duration.between(startTime, Instant.now()),
                phaseBefore.name(),
                newState.phase().name(),
                action.getClass().getSimpleName(),
                extractActionSummary(action)
        );

        traceRecorder.recordStep(traceContext, step);
        return newState;
    }

    /** 根据 AgentPhase 映射 LLM 场景。 */
    private String mapPhaseToScene(AgentPhase phase) {
        var mapping = config.getLoop().getSceneMapping();
        String key = phase.name().toLowerCase();
        return mapping.getOrDefault(key, LlmScene.AGENT_REASONING);
    }

    /** 从 Action 中提取摘要信息。 */
    private String extractActionSummary(Action action) {
        return switch (action) {
            case Action.IntentUnderstood a -> "意图理解: " + a.summary();
            case Action.PlanGenerated a -> "计划生成: %d 步".formatted(a.steps().size());
            case Action.ToolResult a -> "工具调用: " + a.toolId();
            case Action.ReflectionComplete a -> "反思完成: " + (a.satisfied() ? "满意" : "需调整");
            case Action.ResponseGenerated _ -> "响应生成";
            case Action.BudgetExhausted a -> "预算耗尽: " + a.reason();
            case Action.Blocked a -> "护栏阻断: " + a.reason();
            case Action.ErrorRecovery a -> "错误恢复: " + a.errorMessage();
            case Action.SubAgentResult a -> "子 Agent: " + a.skillId();
        };
    }

    /** 异步后处理：会话持久化。 */
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
        executor.close();
    }
}
