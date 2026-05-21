package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.CallbackHelper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.callback.LlmCallPurpose;
import com.lifepilot.agent.callback.NonStreamingCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.*;
import com.lifepilot.agent.execution.ExecutionCompletionPolicy;
import com.lifepilot.agent.execution.ReflectContentBuilder;
import com.lifepilot.agent.execution.ToolExecutionCoordinator;
import com.lifepilot.agent.learning.experience.ExperienceSummarizer;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.suspend.event.ScheduledWakeupEvent;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.web.sse.SseEventBuffer;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.workspace.SessionWorkspaceService;
import com.lifepilot.memory.store.workspace.TaskStateItem;
import com.lifepilot.memory.store.workspace.WorkingSetItem;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * ReAct Agent 循环 — 真正的 Thought → Action → Observation 架构。
 *
 * <p>核心循环每次迭代：
 * <ol>
 *   <li>调用 LLM（禁用自动 tool calling）获取原始响应</li>
 *   <li>检查响应是否包含 tool call 请求</li>
 *   <li>若有 tool call → 记录 ToolCall 步骤 → 手动执行工具 → 记录 Observation 步骤 → 继续循环</li>
 *   <li>若无 tool call（纯文本）→ 记录 Answer 步骤 → 循环结束</li>
 * </ol>
 *
 * <p>通过 {@code ChatModel.call(Prompt)} + {@code internalToolExecutionEnabled=false}
 * 实现手动 tool calling 控制，确保每个工具调用都被显式记录到 ReAct 步骤和 Trace 中。</p>
 *
 * @author zsg
 * @since 2026-03-14
 */
public class ReactAgentLoop implements CallbackHelper {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentLoop.class);
    private static final String DEFAULT_MODEL_ID = "ZhiWei";
    private static final int GRACEFUL_SUMMARY_MAX_STEPS = 16;
    private static final int GRACEFUL_SUMMARY_MAX_DIGEST_CHARS = 16_000;
    private static final int GRACEFUL_SUMMARY_MAX_OBSERVATION_CHARS = 1_200;
    private static final int GRACEFUL_SUMMARY_MAX_INPUT_CHARS = 600;
    private static final int GRACEFUL_FALLBACK_OBSERVATION_CHARS = 260;

    /** 停滞检测排除名单 — 这些工具的重复调用（不同参数）是合理的执行模式。 */
    private static final Set<String> STALL_DETECTION_EXCLUDED_TOOLS = Set.of("web.search");

    // ===== 核心依赖 =====
    private final ContextAssembler contextAssembler;
    private final ProviderMessageBuilder providerMessageBuilder;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;
    private final ExecutionCompletionPolicy completionPolicy;
    private final ToolExecutionCoordinator toolExecutionCoordinator;
    @Nullable private final CompactionEngine compactionEngine;
    @Nullable private final TraceRecorder traceRecorder;

    // ===== 可选依赖（多模态） =====
    @Nullable private final MultimodalRouter multimodalRouter;

    // ===== 可选依赖（挂起-恢复） =====
    @Nullable private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService suspendScheduler;

    // ===== 可选依赖（L1 工作区） =====
    @Nullable private final SessionWorkspaceService workspaceService;

    // ===== 可选依赖（L4 反馈闭环） =====
    @Nullable private final ProceduralMemory proceduralMemory;
    @Nullable private final IntentMatcher intentMatcher;

    // ===== 可选依赖（即时经验补丁） =====
    @Nullable private final ExperienceSummarizer experienceSummarizer;

    // ===== 可选依赖（run(Session, UserMessage) 便捷入口依赖） =====
    // 通过 setter 注入，避免破坏既有 18 参构造器签名；生产环境由 AgentAutoConfiguration 注入，
    // 测试环境若不调用 run() 则无需提供。
    @Nullable private GenerationRouter generationRouter;

    public ReactAgentLoop(
            ContextAssembler contextAssembler,
            ProviderMessageBuilder providerMessageBuilder,
            AgentToolProvider agentToolProvider,
            AgentConfigProperties config,
            ObjectMapper objectMapper,
            @Nullable TraceRecorder traceRecorder,
            @Nullable TranscriptStore transcriptStore,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaDataExtractor mediaDataExtractor,
            @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable ProceduralMemory proceduralMemory,
            @Nullable IntentMatcher intentMatcher,
            @Nullable CompactionEngine compactionEngine,
            SharedScheduler sharedScheduler,
            @Nullable SessionWorkspaceService workspaceService,
            @Nullable ExperienceSummarizer experienceSummarizer) {
        this.contextAssembler = contextAssembler;
        this.providerMessageBuilder = providerMessageBuilder;
        this.agentToolProvider = agentToolProvider;
        this.config = config;
        this.objectMapper = objectMapper;
        this.completionPolicy = new ExecutionCompletionPolicy();
        this.toolExecutionCoordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                objectMapper,
                traceRecorder,
                transcriptStore,
                mediaDataExtractor,
                proceduralMemory,
                intentMatcher,
                config.getLoop().getMaxParallelToolCalls(),
                multimodalRouter,
                workspaceService
        );
        this.compactionEngine = compactionEngine;
        this.traceRecorder = traceRecorder;
        this.multimodalRouter = multimodalRouter;
        this.eventPublisher = eventPublisher;
        this.proceduralMemory = proceduralMemory;
        this.intentMatcher = intentMatcher;
        this.workspaceService = workspaceService;
        this.experienceSummarizer = experienceSummarizer;
        this.suspendScheduler = sharedScheduler.cleanup();
    }

    /** 获取多模态路由器（预留：多模态流式请求）。 */
    @Nullable
    public MultimodalRouter getMultimodalRouter() {
        return multimodalRouter;
    }

    /**
     * 注入文本生成路由器 — 仅在使用 {@link #run(String, UserMessage)} 便捷入口时必需。
     *
     * <p>采用 setter 注入而非构造器注入，避免破坏既有 18 参构造器签名和全量测试用例；
     * 生产环境由 {@code AgentAutoConfiguration} 装配，单元测试若不走 run() 路径可直接忽略。</p>
     *
     * @param generationRouter 文本生成路由器
     */
    public void setGenerationRouter(@Nullable GenerationRouter generationRouter) {
        this.generationRouter = generationRouter;
    }

    /**
     * 注入会话产物持久化仓库 — 委托给 {@link ToolExecutionCoordinator}，让工具执行后
     * 把 {@code ToolResult.artifacts()} 写入 {@code session_artifacts} 表。
     *
     * <p>生产环境由 {@code AgentAutoConfiguration} 装配；测试环境若不需要文件产物
     * 链路可不调用。</p>
     */
    public void setSessionArtifactRepository(
            @Nullable com.lifepilot.conversation.artifact.SessionArtifactRepository repo) {
        this.toolExecutionCoordinator.setSessionArtifactRepository(repo);
    }

    /**
     * 注入 SSE 会话管理器 — 委托给 {@link ToolExecutionCoordinator}，让工具产物登记后
     * 立即推送 {@code artifact-ref} SSE 事件给 Web 端。
     */
    public void setSseSessionManager(@Nullable com.lifepilot.interaction.web.sse.SseSessionManager mgr) {
        this.toolExecutionCoordinator.setSseSessionManager(mgr);
    }

    /** 注入智能层能力评估器 — 记录工具执行结果，供决策引擎使用。 */
    public void setCapabilityAssessor(@Nullable com.lifepilot.agent.intelligence.CapabilityAssessor capabilityAssessor) {
        this.toolExecutionCoordinator.setCapabilityAssessor(capabilityAssessor);
    }

    ProviderMessageBuilder.BuildResult buildProviderMessages(AssembledContext ctx, ReactAgentState state) {
        return providerMessageBuilder.build(ctx, state);
    }

    // ===== 便捷入口：run(Session, UserMessage) =====

    /**
     * 以最小入参运行一次 ReAct 循环，返回结构化的 {@link TurnResult}。
     *
     * <p>本方法是为 SDK 调用方与场景 E2E 测试准备的纯函数式入口，内部复用
     * {@link #coreLoop} — 与 HTTP/SSE 入口（{@code AgentOrchestrator.run}）共享
     * 同一核心循环，不绕过 suspend/resume、retry、预算降级等语义。</p>
     *
     * <p>与 HTTP 入口的差异：
     * <ul>
     *   <li>不做 checkpoint 保存/恢复（每次都是一轮全新的循环）</li>
     *   <li>不持久化 transcript、turn、workspace 进度快照</li>
     *   <li>不发送 SSE 事件（{@link AgentLoopContext} 为纯 no-op 模式）</li>
     *   <li>不做多模态预处理，用户需传入已校验好的消息</li>
     * </ul>
     *
     * <p>调用前需通过 {@link #setGenerationRouter(GenerationRouter)} 注入文本生成路由器，
     * 否则抛出 {@link IllegalStateException}。</p>
     *
     * @param sessionId   会话标识（不可为空）
     * @param userMessage 用户消息（Spring AI {@link UserMessage}，文本通过 {@code getText()} 读取）
     * @return 本轮执行结果快照
     * @throws IllegalArgumentException 入参为 null 或 sessionId 空白
     * @throws IllegalStateException    未通过 setter 注入 GenerationRouter
     */
    public TurnResult run(String sessionId, UserMessage userMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (userMessage == null) {
            throw new IllegalArgumentException("userMessage 不能为空");
        }
        if (generationRouter == null) {
            throw new IllegalStateException(
                    "ReactAgentLoop.run(...) 依赖 GenerationRouter，请先调用 setGenerationRouter 注入");
        }

        // 1. 构造最小 AgentRequest：仅携带会话标识与用户消息，其他参数走默认值
        var request = new AgentRequest(
                userMessage.getText() != null ? userMessage.getText() : "",
                sessionId,
                InteractionSource.system("react-agent-loop-run"));

        // 2. 初始化 ReactAgentState — 与 AgentOrchestrator.run 一致的初始化路径
        var state = ReactAgentState.init(request, Budget.fromConfig(config.getBudget()));

        // 3. 构造无 SSE 的 loopContext（纯内存模式，不推送事件）
        var loopContext = new AgentLoopContext();
        var cancellationToken = new CancellationToken();

        // 4. 构造 NonStreamingCallback — 走与 HTTP 同步入口完全相同的 LLM 调用路径
        var callback = new NonStreamingCallback(
                config, generationRouter, multimodalRouter, request, this);

        // 5. 调用核心循环（与 AgentOrchestrator.run 共享同一方法）
        var loopStart = Instant.now();
        ReactAgentState finalState = coreLoop(
                state, request, null, loopStart, callback, cancellationToken, loopContext);

        // 6. 从 steps 中收集工具调用序列（ToolCall 与 Observation 按 callId / 顺序配对）
        List<TurnResult.ToolInvocation> invocations = collectToolInvocations(finalState);

        // 7. 判断本轮是否"正常结束"：非挂起、非降级终止、且 done=true
        boolean completed = finalState.isDone()
                && !finalState.suspended()
                && finalState.completionMode() != CompletionMode.DEGRADED
                && finalState.terminationReason() == null;

        return new TurnResult(
                finalState.sessionId(),
                finalState.turnId(),
                invocations,
                finalState.finalOutput(),
                completed);
    }

    /**
     * 从 ReAct 步骤序列中提取 ToolCall + Observation 配对，按原始调用顺序汇总。
     *
     * <p>优先通过 {@code callId} 精确匹配；callId 缺失时退回到按顺序的 toolId 首匹配，
     * 与 transcript 回放逻辑保持一致。</p>
     */
    private List<TurnResult.ToolInvocation> collectToolInvocations(ReactAgentState state) {
        var result = new ArrayList<TurnResult.ToolInvocation>();
        var steps = state.steps();
        // Observation 按 callId 建立索引，没有 callId 的放到 fallback 队列
        var observationByCallId = new LinkedHashMap<String, ReactStep.Observation>();
        var fallbackObservations = new ArrayList<ReactStep.Observation>();
        for (var step : steps) {
            if (step instanceof ReactStep.Observation obs) {
                if (obs.callId() != null) {
                    observationByCallId.put(obs.callId(), obs);
                } else {
                    fallbackObservations.add(obs);
                }
            }
        }

        int fallbackCursor = 0;
        for (var step : steps) {
            if (!(step instanceof ReactStep.ToolCall toolCall)) continue;
            ReactStep.Observation matched = null;
            if (toolCall.callId() != null) {
                matched = observationByCallId.remove(toolCall.callId());
            }
            if (matched == null && fallbackCursor < fallbackObservations.size()) {
                matched = fallbackObservations.get(fallbackCursor++);
            }
            String resultJson = matched != null ? matched.output() : "";
            result.add(new TurnResult.ToolInvocation(
                    toolCall.toolId(),
                    toolCall.inputJson() != null ? toolCall.inputJson() : "",
                    resultJson));
        }
        return result;
    }

    // ===== 核心 ReAct 循环 =====

    /**
     * ReAct 核心循环 — 真正的 Thought → Action → Observation 架构。
     *
     * <p>每次迭代：
     * <ol>
     *   <li>调用 LLM（禁用自动 tool calling）获取原始响应</li>
     *   <li>检查 AssistantMessage.hasToolCalls()</li>
     *   <li>若有 tool call → 记录 ToolCall 步骤 → 手动执行 → 媒体提取 → 记录 Observation → 继续</li>
     *   <li>若无 tool call → 记录 Answer → done=true</li>
     * </ol>
     *
     * <p>终止条件（任一满足即退出）：
     * <ol>
     *   <li>LLM 返回纯文本（无 tool call）→ 正常完成</li>
     *   <li>Budget 任一维度超限 → 降级响应</li>
     *   <li>CancellationToken 被触发 → 中断</li>
     *   <li>迭代次数达到 maxIterations → 强制终止</li>
     *   <li>连续失败达到 maxConsecutiveFailures → 强制终止</li>
     * </ol>
     */
    public ReactAgentState coreLoop(
            ReactAgentState state,
            AgentRequest request,
            @Nullable TraceContext traceContext,
            Instant loopStart,
            IterationCallback callback,
            CancellationToken cancellationToken,
            AgentLoopContext loopContext) {

        int maxIterations = config.getLoop().getMaxIterations();
        int maxConsecutiveFailures = config.getLoop().getMaxConsecutiveFailures();
        int consecutiveFailures = 0;
        // 缓存首次组装的上下文 — 记忆检索结果和预算分配在迭代间不变
        AssembledContext cachedContext = null;
        // 缓存工具回调列表 — 工具集在迭代间不变，仅 TRIM_TOOLS 降级时失效重建
        List<ToolCallback> cachedToolCallbacks = null;

        for (int iteration = 0; !state.isDone(); iteration++) {
            // 1. 取消信号检查
            if (cancellationToken.isCancelled() || Thread.currentThread().isInterrupted()) {
                log.info("ReAct 循环被取消: traceId={}, iteration={}", state.traceId(), iteration);
                break;
            }

            // 2. 迭代硬限制
            if (iteration >= maxIterations) {
                log.warn("ReAct 循环达到迭代上限: traceId={}, maxIterations={}",
                        state.traceId(), maxIterations);
                state = DegradedResponseBuilder.terminateWithReason(
                        state, "循环次数达到硬限制: " + maxIterations);
                break;
            }

            // 3. 预算检查：每轮开始前先刷新 elapsed，并在任一维度超限时统一降级终止
            var startBudgetCheck = checkBudgetAndInvalidateCacheIfNeeded(state, loopStart, cachedContext != null);
            state = startBudgetCheck.state();
            if (startBudgetCheck.invalidateCachedContext() && cachedContext != null) {
                // 增量降级：基于已缓存的上下文裁剪，避免重新检索记忆和知识
                cachedContext = cachedContext.degrade(startBudgetCheck.degradationLevel());
                if (startBudgetCheck.degradationLevel() == Budget.DegradationLevel.TRIM_TOOLS) {
                    cachedToolCallbacks = null;
                }
            }
            if (state.isDone()) {
                break;
            }

            // 4. 组装上下文 + 构建消息 + 获取工具回调
            // 首轮组装完整上下文，后续迭代复用缓存（记忆检索和预算分配不变）
            if (cachedContext == null) {
                // 首轮迭代：推送上下文组装 Thought 步骤
                state = state.appendStep(new ReactStep.Progress("正在检索相关记忆和知识…"));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);

                cachedContext = contextAssembler.assemble(state);
                // 传播经验注入 ID 到请求作用域上下文
                if (!cachedContext.injectedEntityIds().isEmpty()) {
                    loopContext.addInjectedEntityIds(cachedContext.injectedEntityIds());
                }
            }
            var assembledContext = cachedContext;
            boolean firstIteration = iteration == 0;
            // 首轮迭代注入用户上传的媒体内容到上下文
            if (firstIteration && hasMultimodalContent(request)) {
                // 仅图片需要 VISION Provider，音频由 MultimodalRouter 的原生音频路由或 STT 处理
                if (hasImageContent(request) && (multimodalRouter == null || !multimodalRouter.isVisionAvailable())) {
                    log.warn("用户上传了图片但无可用 VISION Provider: traceId={}", state.traceId());
                    state = DegradedResponseBuilder.terminateWithReason(
                            state,
                            "当前没有配置支持图片理解的模型（VISION Provider），无法处理您上传的图片。" +
                                    "请先在系统设置中配置支持视觉能力的模型提供商，然后重试。");
                    break;
                }
                assembledContext = assembledContext.withMediaContents(request.mediaContents());
            }
            // 非首轮迭代：有 pendingMedia 时注入工具产生的媒体
            else if (state.pendingMedia() != null && !state.pendingMedia().isEmpty()) {
                assembledContext = assembledContext.withMediaContents(state.pendingMedia());
            }
            var messageBuildResult = buildProviderMessages(assembledContext, state);
            var messages = messageBuildResult.messages();
            if (messageBuildResult.hygieneReport().hasRepairs()) {
                log.debug("provider 消息卫生化已生效: traceId={}, originalCount={}, cleanedCount={}, " +
                                "droppedEmptyAssistant={}, droppedOrphanToolResponses={}, droppedAdditionalSystems={}",
                        state.traceId(),
                        messageBuildResult.hygieneReport().originalCount(),
                        messageBuildResult.hygieneReport().cleanedCount(),
                        messageBuildResult.hygieneReport().droppedEmptyAssistantMessages(),
                        messageBuildResult.hygieneReport().droppedOrphanToolResponses(),
                        messageBuildResult.hygieneReport().droppedAdditionalSystemMessages());
            }
            if (cachedToolCallbacks == null) {
                cachedToolCallbacks = agentToolProvider.getToolCallbacks(
                        state, loopContext.getStreamId(), loopContext::addArtifactRefsAsToolArtifacts);
            }
            var toolCallbacks = cachedToolCallbacks;
            var callPurpose = resolveLlmCallPurpose(state);
            var llmToolCallbacks = state.taskMode() == AgentTaskMode.ANSWER
                    ? List.<ToolCallback>of()
                    : toolCallbacks;

            log.debug("ReAct 迭代开始: traceId={}, iteration={}, stepCount={}, toolCount={}, purpose={}",
                    state.traceId(), iteration, state.stepCount(), toolCallbacks.size(), callPurpose);

            // 5. 调用 LLM（不自动执行 tool call）
            // 推送思考中 Thought 步骤
            state = state.appendStep(new ReactStep.Progress("正在思考回答…"));
            pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);

            // 构造有效请求：将当前迭代的媒体内容传递给 callLlm
            var effectiveRequest = assembledContext.mediaContents() != null && !assembledContext.mediaContents().isEmpty()
                    ? request.withMediaContents(assembledContext.mediaContents())
                    : request;
            var iterationStart = Instant.now();
            ChatResponse chatResponse;
            try {
                chatResponse = callback.callLlm(
                        effectiveRequest, messages, llmToolCallbacks, traceContext, callPurpose);
            } catch (Exception e) {
                log.error("LLM 调用异常: traceId={}, iteration={}, error={}",
                        state.traceId(), iteration, e.getMessage());
                // 流式连接异常（EOF/网络中断）走普通失败路径 —— 偶发 EOF（DeepSeek 思考久
                // 服务端 idle timeout 切连接、网络抖动）下次调用大概率成功，让 consecutiveFailures
                // 计数器主导：偶发 1-2 次 EOF 自动重试救回，真持续故障 3 次连续才终止。
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    state = DegradedResponseBuilder.terminateWithReason(
                            state, "连续 LLM 调用失败达到上限: " + maxConsecutiveFailures);
                    break;
                }
                state = state.appendStep(new ReactStep.Observation(
                        "llm", null, false, "LLM 调用失败: " + e.getMessage(), 0, null));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                continue;
            }
            var iterationDuration = Duration.between(iterationStart, Instant.now());

            // 清除 pendingMedia 缓冲区（已嵌入到本次 messages 中，避免后续迭代重复嵌入）
            if (state.pendingMedia() != null && !state.pendingMedia().isEmpty()) {
                state = state.clearPendingMedia();
            }

            // 6. 解析 LLM 响应
            if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
                log.warn("LLM 返回空响应: traceId={}, iteration={}", state.traceId(), iteration);
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    state = DegradedResponseBuilder.terminateWithReason(
                            state, "连续 LLM 空响应达到上限: " + maxConsecutiveFailures);
                    break;
                }
                state = state.appendStep(new ReactStep.Observation(
                        "llm", null, false, "LLM 返回空响应", 0, null));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                continue;
            }
            var assistantMessage = chatResponse.getResult().getOutput();
            int responseTokens = estimateTokens(chatResponse);
            String providerId = callback.getProviderId();
            String modelId = callback.getModelId();

            // 6.5 提取 finishReason，判断响应是否被截断
            String finishReason = extractFinishReason(chatResponse);
            boolean truncated = isResponseTruncated(finishReason);
            if (truncated) {
                log.warn("LLM 响应被截断（finishReason={}）: traceId={}, iteration={}",
                        finishReason, state.traceId(), iteration);
            }

            // 记录 LLM 调用到 Trace — 流式回调已在内部记录，跳过避免重复
            if (!callback.recordsLlmStep()) {
                recordLlmStep(traceContext, state.stepCount(), iterationStart,
                        iterationDuration, chatResponse, providerId, modelId,
                        effectiveRequest.temperature());
            }

            // ── 7. 分支: Tool Call (Action) vs 纯文本 (Answer) ──
            if (assistantMessage.hasToolCalls()) {
                // === ReAct: Action — 执行工具调用 ===
                var toolCalls = assistantMessage.getToolCalls();

                // 7a. 记录 LLM 伴随文本为 Thought
                String thoughtText = assistantMessage.getText();
                if (thoughtText != null && !thoughtText.isBlank()) {
                    state = state.appendStep(new ReactStep.Thought(thoughtText));
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                }

                // 7b. 工具执行前快照 — 事后检测 ToolExecutionCoordinator 是否发现新工具或合并 Skill 指南
                int preExecDiscoveredCount = state.discoveredToolIds() != null ? state.discoveredToolIds().size() : 0;
                String preExecSkillContent = state.loadedSkillContent();

                // 7c. 执行工具批量调用
                // 透传 reasoning_content（DeepSeek V4 等多轮契约要求回传，非 thinking 模式为空串）
                String llmReasoningContent = callback.getFinalReasoningContent();
                state = toolExecutionCoordinator.executeBatch(
                        state, toolCalls, toolCallbacks, traceContext, cancellationToken,
                        loopContext, this::appendAndPublishStep, llmReasoningContent);

                // 7d. 工具 / Skill 缓存失效 — 新发现工具或 Skill 指南变更时重建缓存
                int postExecDiscoveredCount = state.discoveredToolIds() != null ? state.discoveredToolIds().size() : 0;
                if (postExecDiscoveredCount > preExecDiscoveredCount) {
                    cachedToolCallbacks = null;
                }
                if (!Objects.equals(preExecSkillContent, state.loadedSkillContent())) {
                    cachedContext = null;
                }

                // 7e. 挂起检测 — 工具返回 _suspend 信号时跳出循环
                if (state.suspended()) {
                    log.info("Agent 进入挂起态: traceId={}, reason={}", state.traceId(), state.suspendReason());
                    state = state.appendStep(new ReactStep.Suspend(
                            state.suspendReason(), Instant.now(), state.stepCount()));
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                    state = state.toBuilder()
                            .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                            .build();
                    break;
                }
                if (cancellationToken.isCancelled()) break;

                // 7f. 执行回顾 — 工具执行完毕后评估是否需要反思调整策略
                var maybeReflect = evaluateReflectionTrigger(state, iteration);
                if (maybeReflect != null) {
                    state = appendAndPublishStep(state, maybeReflect, loopContext);
                    persistReflectToWorkspace(state, iteration);
                    if (experienceSummarizer != null) {
                        final var snapshotState = state;
                        final var reflectContent = maybeReflect.content();
                        final var reflectTrigger = maybeReflect.trigger();
                        Thread.startVirtualThread(() -> experienceSummarizer.quickLearn(snapshotState, reflectContent, reflectTrigger));
                    }
                    if (workspaceService != null && state.sessionId() != null) {
                        try {
                            String reflectSummary = maybeReflect.content().length() > 300
                                    ? maybeReflect.content().substring(0, 300) + "…"
                                    : maybeReflect.content();
                            workspaceService.saveWorkingSet(state.sessionId(), new WorkingSetItem(
                                    "反思结论: " + maybeReflect.trigger().name(),
                                    reflectSummary,
                                    Map.of("trigger", maybeReflect.trigger().name(), "iteration", iteration),
                                    70, state.traceId(), state.traceId(), null));
                        } catch (Exception e) {
                            log.debug("反思工作区持久化失败: {}", e.getMessage());
                        }
                    }
                    cachedContext = null;
                    cachedToolCallbacks = null;
                }

                // 7g. 扣减 Token 预算，重置失败计数
                state = state.toBuilder().budget(state.budget().deductTokens(responseTokens)).build();
                consecutiveFailures = 0;

            } else {
                // === ReAct: Answer — 评估文本响应（完成 / 挂起 / 拒绝提前结束 / 直接回答）===
                var textResult = handleTextResponse(
                        state, assistantMessage.getText(), truncated, finishReason,
                        responseTokens, consecutiveFailures, maxConsecutiveFailures,
                        iteration, request, loopContext, callback);
                state = textResult.state();
                consecutiveFailures = textResult.consecutiveFailures();
                if (textResult.shouldInvalidateCachedContext()) {
                    cachedContext = null;
                    cachedToolCallbacks = null;
                }
                if (textResult.shouldBreak()) {
                    break;
                }
            }

            // ── 8. 迭代收尾: 步数+1、预算刷新、中途压缩、渐进式降级 ──
            state = refreshBudgetElapsed(advanceBudgetStep(state), loopStart);
            if (!state.isDone()) {
                if (maybeCompactMidLoop(state, iteration, cachedContext != null)) {
                    state = appendAndPublishStep(
                            state,
                            new ReactStep.Progress("上下文较长，已压缩历史并重建执行状态…"),
                            loopContext
                    );
                    cachedContext = null;
                    cachedToolCallbacks = null;
                }
                var endBudgetCheck = checkBudgetAndInvalidateCacheIfNeeded(state, loopStart, cachedContext != null);
                state = endBudgetCheck.state();
                if (endBudgetCheck.invalidateCachedContext() && cachedContext != null) {
                    cachedContext = cachedContext.degrade(endBudgetCheck.degradationLevel());
                    if (endBudgetCheck.degradationLevel() == Budget.DegradationLevel.TRIM_TOOLS) {
                        cachedToolCallbacks = null;
                    }
                }
                if (state.isDone()) {
                    break;
                }
            }
        }

        if (state.isDone() && state.completionMode() == CompletionMode.DEGRADED
                && hasMeaningfulProgress(state)) {
            state = buildGracefulSummary(state, request);
        }

        return state;
    }

    /** 预算降级终止时，用已收集的信息做一次最终总结再返回。 */
    private ReactAgentState buildGracefulSummary(ReactAgentState state,
                                                  AgentRequest request) {
        String deterministicFallback = buildDeterministicGracefulSummary(state);
        try {
            String digest = buildGracefulSummaryDigest(state);
            String reason = state.terminationReason() != null && !state.terminationReason().isBlank()
                    ? state.terminationReason()
                    : "预算超限或执行中断";
            String summaryPrompt = """
                    你正在做一个任务的收尾总结。

                    背景：本轮任务已中断，原因：%s
                    用户原始请求：%s

                    以下为本轮执行过程中已记录的步骤与工具输出摘要（仅供你总结使用）：
                    %s

                    请基于以上内容，给用户一个结构化的总结：
                    1. 已完成的工作（列出具体成果）
                    2. 未完成的部分（哪些还没做）
                    3. 建议下一步（用户可以继续做什么）

                    约束：
                    - 直接输出总结，不要调用工具
                    - 不要编造不存在的工具结果或外部信息
                    - 若信息不足，请明确写“目前缺少哪些关键信息”
                    """.formatted(reason, safeUserRequestText(state.goal()), digest);

            String summary = tryCallNonStreamingForGracefulSummary(request, summaryPrompt);
            if (summary != null && !summary.isBlank()) {
                log.info("优雅终止总结已生成: traceId={}, summaryLen={}",
                        state.traceId(), summary.length());
                return state.toBuilder()
                        .finalOutput(summary.strip())
                        .completionMode(CompletionMode.DEGRADED)
                        .build();
            }
        } catch (Exception e) {
            log.warn("优雅终止总结生成失败，回退确定性摘要: traceId={}, error={}",
                    state.traceId(), e.getMessage());
        }
        return state.toBuilder()
                .finalOutput(deterministicFallback)
                .completionMode(CompletionMode.DEGRADED)
                .build();
    }

    /** 判断是否有足够进展值得做总结（至少有成功的工具调用）。 */
    private static boolean hasMeaningfulProgress(ReactAgentState state) {
        return state.steps().stream().anyMatch(
                s -> s instanceof ReactStep.Observation obs && obs.success());
    }

    private @Nullable String tryCallNonStreamingForGracefulSummary(AgentRequest request,
                                                                   String summaryPrompt) {
        // 使用独立的非流式调用生成总结，避免复用 StreamingCallback 导致的连接异常/状态污染。
        if (generationRouter == null) {
            return null;
        }
        try {
            // 走独立 CHAT 能力，禁用缓存并设置短超时，避免收尾摘要再次拖成长任务。
            var llm = generationRouter.call(
                    "graceful_summary",
                    summaryPrompt,
                    null,
                    request.preferredProvider(),
                    null,
                    com.lifepilot.modelservice.model.GenerationCapability.CHAT,
                    Duration.ofSeconds(60),
                    true
            );
            return llm != null ? llm.content() : null;
        } catch (Exception e) {
            log.debug("优雅终止总结非流式调用失败: {}", e.getMessage());
            return null;
        }
    }

    private static String safeUserRequestText(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        if (t.length() > GRACEFUL_SUMMARY_MAX_INPUT_CHARS) {
            return t.substring(0, GRACEFUL_SUMMARY_MAX_INPUT_CHARS) + "…";
        }
        return t;
    }

    private static String buildGracefulSummaryDigest(ReactAgentState state) {
        var buf = new StringBuilder();
        buf.append("<steps>\n");

        List<ReactStep> steps = state.steps();
        int start = Math.max(0, steps.size() - GRACEFUL_SUMMARY_MAX_STEPS);
        for (int i = start; i < steps.size(); i++) {
            ReactStep step = steps.get(i);
            buf.append("- [").append(i).append("] ");
            switch (step) {
                case ReactStep.Progress p -> buf.append("Progress: ").append(oneLine(p.content(), 160));
                case ReactStep.Thought t -> buf.append("Thought: ").append(oneLine(t.content(), 220));
                case ReactStep.ToolCall tc -> buf.append("ToolCall: ")
                        .append(tc.toolId())
                        .append(" input=").append(oneLine(tc.inputJson(), GRACEFUL_SUMMARY_MAX_INPUT_CHARS));
                case ReactStep.Observation obs -> {
                    buf.append("Observation: ")
                            .append(obs.toolId());
                    if (obs.toolName() != null && !obs.toolName().isBlank()) {
                        buf.append(" (").append(obs.toolName()).append(")");
                    }
                    buf.append(" success=").append(obs.success());
                    if (obs.output() != null && !obs.output().isBlank()) {
                        buf.append(" output=").append(oneLine(obs.output(), GRACEFUL_SUMMARY_MAX_OBSERVATION_CHARS));
                    }
                }
                case ReactStep.Answer a -> buf.append("Answer: ").append(oneLine(a.content(), 500));
                case ReactStep.Suspend s -> buf.append("Suspend: ").append(s.reason());
                case ReactStep.Resume r -> buf.append("Resume: ").append(r.payload().getClass().getSimpleName());
                case ReactStep.Reflect r -> buf.append("Reflect: ").append(oneLine(r.content(), 260));
            }
            buf.append('\n');
            if (buf.length() >= GRACEFUL_SUMMARY_MAX_DIGEST_CHARS) {
                buf.append("...[digest truncated]...\n");
                break;
            }
        }
        buf.append("</steps>");
        return buf.toString();
    }

    private static String buildDeterministicGracefulSummary(ReactAgentState state) {
        String reason = state.terminationReason() != null && !state.terminationReason().isBlank()
                ? state.terminationReason()
                : "执行中断";
        int successTools = (int) state.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation obs && obs.success())
                .count();
        int failedTools = (int) state.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation obs && !obs.success() && !"llm".equals(obs.toolId()))
                .count();

        String tools = summarizeToolOutcomesForFallback(state);
        return """
                本轮处理已中断。

                原始任务：%s
                原因：%s
                已记录成功工具调用：%d
                已记录失败工具调用：%d

                已完成的步骤（摘要）：
                %s

                建议下一步：
                1. 点击继续执行，系统会尝试沿着当前进度继续。
                2. 若仍反复中断，请缩小任务范围或降低一次性输出长度，再重试。
                """.formatted(safeUserRequestText(state.goal()), reason, successTools, failedTools, tools).trim();
    }

    private static String summarizeToolOutcomesForFallback(ReactAgentState state) {
        var lines = new ArrayList<String>();
        for (ReactStep step : state.steps()) {
            if (!(step instanceof ReactStep.Observation obs)) {
                continue;
            }
            if ("llm".equals(obs.toolId())) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append("- ").append(obs.toolId());
            if (obs.toolName() != null && !obs.toolName().isBlank()) {
                line.append(" (").append(obs.toolName()).append(")");
            }
            line.append(" success=").append(obs.success());
            if (obs.output() != null && !obs.output().isBlank()) {
                line.append(" output=").append(oneLine(obs.output(), GRACEFUL_FALLBACK_OBSERVATION_CHARS));
            }
            lines.add(line.toString());
            if (lines.size() >= 8) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return "- 暂无（本轮未成功执行工具或工具结果未记录）";
        }
        return String.join("\n", lines);
    }

    private static String oneLine(@Nullable String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > maxChars) {
            return t.substring(0, maxChars) + "…";
        }
        return t;
    }

    /**
     * 文本响应阶段处理结果。
     *
     * @param state                        更新后的 Agent 状态（可能设置了 done/suspended）
     * @param consecutiveFailures          更新后的连续失败计数
     * @param shouldBreak                  是否应跳出主循环（挂起/降级终止时）
     * @param shouldInvalidateCachedContext 是否需要重建缓存上下文（拒绝提前结束时）
     */
    private record TextResponseResult(
            ReactAgentState state,
            int consecutiveFailures,
            boolean shouldBreak,
            boolean shouldInvalidateCachedContext
    ) {}

    /**
     * 判定本轮 LLM 调用的流式可见性。
     *
     * <p>AUTO / EXECUTION 都是常规 ReAct 轮：保留主答案流式能力，同时由流式回调
     * 在真正检测到工具调用时隔离伴随文本。只有显式 ANSWER 模式才关闭工具并进入
     * 最终答案流。</p>
     */
    private LlmCallPurpose resolveLlmCallPurpose(ReactAgentState state) {
        if (state.taskMode() == AgentTaskMode.ANSWER) {
            return LlmCallPurpose.FINAL_ANSWER;
        }
        return LlmCallPurpose.AGENT_STEP;
    }

    /**
     * 处理 LLM 纯文本响应（非工具调用）。
     *
     * <p>根据 {@link ExecutionCompletionPolicy} 的评估结果分流到四种处置：
     * <ul>
     *   <li>{@code EXPLICIT_TERMINAL} — 模型主动声明任务完成</li>
     *   <li>{@code SUSPEND_FOR_USER_INPUT} — 模型请求用户补充信息</li>
     *   <li>{@code REJECT_IMPLICIT_TERMINATION} — 疑似提前结束，注入反思提示</li>
     *   <li>默认 — 纯文本直接回答</li>
     * </ul>
     *
     * <p>截断的响应和空内容也在此统一处理，不暴露给上层。</p>
     */
    private TextResponseResult handleTextResponse(
            ReactAgentState state,
            String content,
            boolean truncated,
            @Nullable String finishReason,
            int responseTokens,
            int consecutiveFailures,
            int maxConsecutiveFailures,
            int iteration,
            AgentRequest request,
            AgentLoopContext loopContext,
            IterationCallback callback) {

        // 有内容但非截断 → 走完成策略评估
        if (content != null && !content.isBlank()) {
            if (truncated) {
                // 截断文本不能当完整答案 — 作为 Thought 记录，让 LLM 在下一轮继续
                state = state.appendStep(new ReactStep.Thought(content));
                pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                state = state.toBuilder()
                        .budget(state.budget().deductTokens(responseTokens))
                        .build();
                return new TextResponseResult(state, 0, false, false);
            }

            var completionEvaluation = completionPolicy.evaluate(request, state, content);
            return switch (completionEvaluation.disposition()) {
                case EXPLICIT_TERMINAL -> {
                    String visibleContent = completionEvaluation.userVisibleContent() != null
                            ? completionEvaluation.userVisibleContent() : content;
                    CompletionReason completionReason = completionEvaluation.completionReason();
                    callback.publishVisibleContent(visibleContent);
                    state = appendAndPublishStep(state, new ReactStep.Answer(visibleContent), loopContext);
                    state = state.toBuilder()
                            .done(true)
                            .finalOutput(visibleContent)
                            .terminationReason(completionEvaluation.terminationReason())
                            .completionReason(completionReason)
                            .budget(state.budget().deductTokens(responseTokens))
                            .build();
                    log.info("ReAct 循环完成: traceId={}, iterations={}, stepCount={}, tokensUsed={}, " +
                                    "taskMode={}, completionReason={}, preview={}",
                            state.traceId(), iteration + 1, state.stepCount(),
                            state.budget().tokensUsed(), state.taskMode(), completionReason,
                            previewForLog(visibleContent));
                    yield new TextResponseResult(state, consecutiveFailures, false, false);
                }
                case SUSPEND_FOR_USER_INPUT -> {
                    String visibleContent = completionEvaluation.userVisibleContent() != null
                            ? completionEvaluation.userVisibleContent() : content;
                    String suspendPrompt = completionEvaluation.suspendPrompt() != null
                            ? completionEvaluation.suspendPrompt() : visibleContent;
                    state = appendAndPublishStep(state, new ReactStep.Answer(visibleContent), loopContext);
                    state = state.toBuilder()
                            .finalOutput(visibleContent)
                            .terminationReason(completionEvaluation.terminationReason())
                            .completionReason(CompletionReason.SUSPENDED)
                            .completionMode(CompletionMode.SUSPENDED)
                            .budget(state.budget().deductTokens(responseTokens))
                            .build()
                            .suspend(new SuspendReason.ExternalDataWait("__await_user_input__", suspendPrompt));
                    state = appendAndPublishStep(state, new ReactStep.Suspend(
                            state.suspendReason(), Instant.now(), state.stepCount()), loopContext);
                    log.info("ReAct 循环挂起等待用户补充: traceId={}, iterations={}, stepCount={}, preview={}",
                            state.traceId(), iteration + 1, state.stepCount(), previewForLog(visibleContent));
                    yield new TextResponseResult(state, consecutiveFailures, true, false);
                }
                case REJECT_IMPLICIT_TERMINATION -> {
                    int rejectCount = state.earlyStopRejectCount() + 1;
                    log.warn("疑似提前结束已拒绝: traceId={}, iteration={}, rejectCount={}, preview={}",
                            state.traceId(), iteration, rejectCount, previewForLog(content));
                    if (rejectCount >= config.getLoop().getMaxEarlyStopRejects()) {
                        state = DegradedResponseBuilder.terminateWithReason(
                                state.toBuilder().finalOutput(content).build(),
                                "多步执行任务未满足结束协议，系统已拒绝提前结束",
                                CompletionReason.EARLY_STOP_REJECTED);
                        yield new TextResponseResult(state, consecutiveFailures, true, false);
                    }
                    state = state.appendStep(new ReactStep.Thought(
                            "当前回复缺少终态控制信息。如果任务已经完成，请在自然语言正文后追加 " +
                                    "`<completion_control>done</completion_control>`；如果明确阻塞，请追加 " +
                                    "`<completion_control>blocked</completion_control>`；如果还要继续，就直接调用下一步工具。"));
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                    state = state.toBuilder()
                            .earlyStopRejectCount(rejectCount)
                            .budget(state.budget().deductTokens(responseTokens))
                            .build();
                    yield new TextResponseResult(state, 0, false, true);
                }
                default -> {
                    // DIRECT_ANSWER — 无特殊协议的纯文本答案
                    String visibleContent = completionEvaluation.userVisibleContent() != null
                            ? completionEvaluation.userVisibleContent() : content;
                    callback.publishVisibleContent(visibleContent);
                    state = appendAndPublishStep(state, new ReactStep.Answer(visibleContent), loopContext);
                    state = state.toBuilder()
                            .done(true)
                            .finalOutput(visibleContent)
                            .completionReason(CompletionReason.DIRECT_ANSWER)
                            .budget(state.budget().deductTokens(responseTokens))
                            .build();
                    log.info("ReAct 循环完成: traceId={}, iterations={}, stepCount={}, tokensUsed={}, " +
                                    "taskMode={}, completionReason={}, preview={}",
                            state.traceId(), iteration + 1, state.stepCount(),
                            state.budget().tokensUsed(), state.taskMode(), CompletionReason.DIRECT_ANSWER,
                            previewForLog(visibleContent));
                    yield new TextResponseResult(state, consecutiveFailures, false, false);
                }
            };
        }

        // 空内容 — 记录 Observation 而非静默跳过
        log.warn("LLM 返回空内容: traceId={}, iteration={}, finishReason={}",
                state.traceId(), iteration, finishReason);
        state = state.appendStep(new ReactStep.Observation(
                "llm", null, false,
                finishReason != null
                        ? "LLM 返回空内容（finishReason=" + finishReason + "）"
                        : "LLM 返回空内容",
                0, null));
        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
        consecutiveFailures++;
        if (consecutiveFailures >= maxConsecutiveFailures) {
            state = DegradedResponseBuilder.terminateWithReason(
                    state, "连续空响应达到上限: " + maxConsecutiveFailures);
            return new TextResponseResult(state, consecutiveFailures, true, false);
        }
        return new TextResponseResult(state, consecutiveFailures, false, false);
    }

    /**
     * 刷新预算状态，并在达到渐进式降级阈值时通知上层丢弃缓存上下文。
     *
     * <p>这样下一轮重新 assemble 时才能应用压缩历史、裁剪工具或跳过记忆等预算策略。
     */
    private BudgetCheckResult checkBudgetAndInvalidateCacheIfNeeded(
            ReactAgentState state, Instant loopStart, boolean hasCachedContext) {
        state = refreshBudgetElapsed(state, loopStart);

        boolean invalidateCachedContext = false;
        Budget.DegradationLevel degradation = state.budget().degradationLevel();
        Budget.DegradationLevel reportedLevel = null;
        if ((degradation == Budget.DegradationLevel.COMPRESS_HISTORY
                || degradation == Budget.DegradationLevel.TRIM_TOOLS
                || degradation == Budget.DegradationLevel.SKIP_MEMORY)
                && hasCachedContext) {
            log.info("预算渐进式降级: traceId={}, level={}, tokenUtilization={}%",
                    state.traceId(), degradation, (int) (state.budget().tokenUtilization() * 100));
            invalidateCachedContext = true;
            reportedLevel = degradation;
        }

        if (state.budget().exceeded()) {
            log.warn("ReAct 循环预算超限: traceId={}, reason={}",
                    state.traceId(), state.budget().exceedReason());
            state = DegradedResponseBuilder.terminateWithReason(state, state.budget().exceedReason());
        }
        return new BudgetCheckResult(state, invalidateCachedContext, reportedLevel);
    }

    private boolean maybeCompactMidLoop(ReactAgentState state, int iteration, boolean hasCachedContext) {
        if (!hasCachedContext || compactionEngine == null || state.sessionId() == null || state.sessionId().isBlank()) {
            return false;
        }
        if (state.suspended() || state.isDone()) {
            return false;
        }
        if (iteration <= 0 && state.budget().degradationLevel() == com.lifepilot.agent.model.Budget.DegradationLevel.NORMAL) {
            return false;
        }
        try {
            boolean compacted = compactionEngine.compactIfNeeded(
                    state.sessionId(),
                    state.traceId(),
                    state.preferredProvider()
            );
            if (compacted) {
                log.info("ReAct 循环中途压缩生效: traceId={}, iteration={}", state.traceId(), iteration);
            }
            return compacted;
        } catch (Exception e) {
            log.warn("ReAct 循环中途压缩失败: traceId={}, iteration={}, error={}",
                    state.traceId(), iteration, e.getMessage());
            return false;
        }
    }

    /** 在每轮迭代结束后推进一步预算计数。 */
    private ReactAgentState advanceBudgetStep(ReactAgentState state) {
        return state.toBuilder()
                .budget(state.budget().incrementStep())
                .build();
    }

    /** 用当前时间刷新预算中的 elapsed 值。 */
    private ReactAgentState refreshBudgetElapsed(ReactAgentState state, Instant loopStart) {
        return state.toBuilder()
                .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                .build();
    }

    /** 统一追加步骤并同步推送对应的 REASONING SSE 事件。 */
    private ReactAgentState appendAndPublishStep(ReactAgentState state,
                                                 ReactStep step,
                                                 AgentLoopContext loopContext) {
        state = state.appendStep(step);
        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
        return state;
    }

    // ===== 执行回顾（Reflect）=====

    /**
     * 评估当前迭代是否需要注入反思步骤。
     *
     * @return Reflect 步骤（如果应触发），否则 null
     */
    @Nullable
    private ReactStep.Reflect evaluateReflectionTrigger(ReactAgentState state, int iteration) {
        var loopConfig = config.getLoop();

        // 防重复：上一步已是 Reflect 则跳过
        if (!state.steps().isEmpty() && state.steps().getLast() instanceof ReactStep.Reflect) {
            return null;
        }

        // 1. 工具失败触发（即使预算紧张也触发，失败反思价值高且内容短）
        if (loopConfig.isReflectOnToolFailure() && hasRecentToolFailure(state)) {
            return new ReactStep.Reflect(
                    ReflectContentBuilder.buildContent(state, iteration, ReactStep.ReflectTrigger.TOOL_FAILURE),
                    ReactStep.ReflectTrigger.TOOL_FAILURE);
        }

        // 预算门控：非 NORMAL 降级时跳过周期性和停滞触发
        if (state.budget().degradationLevel() != Budget.DegradationLevel.NORMAL) {
            return null;
        }

        // 2. 停滞检测
        if (detectStall(state, loopConfig.getStallDetectionThreshold())) {
            return new ReactStep.Reflect(
                    ReflectContentBuilder.buildContent(state, iteration, ReactStep.ReflectTrigger.STALL_DETECTED),
                    ReactStep.ReflectTrigger.STALL_DETECTED);
        }

        // 3. 周期性触发
        if (iteration >= loopConfig.getReflectAfterIterations()
                && (iteration - loopConfig.getReflectAfterIterations()) % loopConfig.getReflectInterval() == 0) {
            return new ReactStep.Reflect(
                    ReflectContentBuilder.buildContent(state, iteration, ReactStep.ReflectTrigger.PERIODIC),
                    ReactStep.ReflectTrigger.PERIODIC);
        }

        return null;
    }

    /** 检查当前批次（最近连续的 ToolCall/Observation 序列）是否有失败的 Observation。 */
    private boolean hasRecentToolFailure(ReactAgentState state) {
        var steps = state.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            var step = steps.get(i);
            if (step instanceof ReactStep.Observation obs) {
                if (!obs.success()) return true;
            } else if (step instanceof ReactStep.ToolCall) {
                // 仍在当前批次内，继续
            } else {
                break; // 遇到非 ToolCall/Observation 步骤，表示已离开当前批次
            }
        }
        return false;
    }

    /** 检测是否陷入停滞（最近 N 个 ToolCall 使用相同工具）。 */
    private boolean detectStall(ReactAgentState state, int threshold) {
        var steps = state.steps();
        var recentToolIds = new java.util.ArrayList<String>(threshold);
        for (int i = steps.size() - 1; i >= 0 && recentToolIds.size() < threshold; i--) {
            if (steps.get(i) instanceof ReactStep.ToolCall tc) {
                recentToolIds.add(tc.toolId());
            }
        }
        if (recentToolIds.size() < threshold) return false;
        String first = recentToolIds.getFirst();
        if (STALL_DETECTION_EXCLUDED_TOOLS.contains(first)) return false;
        return recentToolIds.stream().allMatch(first::equals);
    }

    /** 将反思进度快照写入 L1 工作区。 */
    private void persistReflectToWorkspace(ReactAgentState state, int iteration) {
        if (workspaceService == null || state.sessionId() == null || state.sessionId().isBlank()) {
            return;
        }
        try {
            workspaceService.saveTaskState(state.sessionId(), new TaskStateItem(
                    "执行进度",
                    ReflectContentBuilder.buildTaskStateSummary(state, iteration),
                    null,
                    0,
                    state.traceId(),
                    state.traceId(),
                    null
            ));
        } catch (Exception e) {
            log.debug("写入工作区进度快照失败，降级跳过: error={}", e.getMessage());
        }
    }

    /** 预算检查结果：同时返回新 state 以及是否需要丢弃缓存上下文。 */
    private record BudgetCheckResult(
            ReactAgentState state,
            boolean invalidateCachedContext,
            @Nullable Budget.DegradationLevel degradationLevel
    ) {
    }

    /**
     * 手动执行单个 tool call，记录 ToolCall + Observation 步骤。
     *
     * <p>流程：
     * <ol>
     *   <li>在 toolCallbacks 中查找匹配的 ToolCallback</li>
     *   <li>记录 ToolCall 步骤</li>
     *   <li>调用 ToolCallback.call(arguments)</li>
     *   <li>媒体数据提取（如有）</li>
     *   <li>记录 Observation 步骤</li>
     *   <li>记录 ToolCallStep 到 Trace</li>
     * </ol>
     *
     * @param state             当前状态
     */
    public void scheduleWakeupIfNeeded(ReactAgentState state) {
        if (state.suspendReason() instanceof SuspendReason.ScheduledWakeup sw && eventPublisher != null) {
            var delay = Duration.between(Instant.now(), sw.wakeupAt());
            if (delay.isNegative() || delay.isZero()) {
                // 唤醒时间已过，立即发布
                eventPublisher.publishEvent(new ScheduledWakeupEvent(state.traceId(), Instant.now()));
                log.info("ScheduledWakeup 唤醒时间已过，立即发布恢复事件: traceId={}", state.traceId());
            } else {
                String traceId = state.traceId();
                suspendScheduler.schedule(() -> {
                    eventPublisher.publishEvent(new ScheduledWakeupEvent(traceId, Instant.now()));
                    log.info("ScheduledWakeup 延迟任务触发: traceId={}", traceId);
                }, delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                log.info("ScheduledWakeup 延迟任务已注册: traceId={}, wakeupAt={}, delayMs={}",
                        state.traceId(), sw.wakeupAt(), delay.toMillis());
            }
        }
    }

    /**
     * 将 SuspendReason 格式化为人类可读的描述文本。
     *
     * @param reason 挂起原因
     * @return 格式化后的描述文本
     */
    public String formatSuspendReason(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait w ->
                    "等待工作流完成 [%s] %s".formatted(w.executionId(), w.workflowName());
            case SuspendReason.UserConfirmation u ->
                    "等待用户确认工具执行 [%s] 风险等级: %s".formatted(u.toolId(), u.riskLevel());
            case SuspendReason.RemoteDelegation r ->
                    "等待远程 Agent 返回 [%s] 目标: %s".formatted(r.remoteTaskId(), r.delegatedGoal());
            case SuspendReason.ScheduledWakeup s ->
                    "定时唤醒 [%s] 原因: %s".formatted(s.wakeupAt(), s.reason());
            case SuspendReason.ExternalDataWait e ->
                    "等待外部数据就绪 [%s] %s".formatted(e.dataSourceId(), e.description());
            case SuspendReason.BrowserTakeover bt ->
                    "等待浏览器人工接管 [%s] %s".formatted(bt.sessionId(), bt.reason());
        };
    }

    // ===== 挂起-恢复：通用恢复入口 =====

    /**
     * 校验 ResumePayload 与 SuspendReason 的配对正确性。
     *
     * <p>使用 pattern matching switch 穷举 5 对类型匹配，
     * 不匹配时抛出 IllegalArgumentException。</p>
     *
     * @param reason  挂起原因
     * @param payload 恢复载荷
     * @throws IllegalArgumentException 类型不匹配时抛出
     */
    public void validateResumePayload(SuspendReason reason, ResumePayload payload) {
        boolean valid = switch (reason) {
            case SuspendReason.WorkflowWait _ -> payload instanceof ResumePayload.WorkflowResult;
            case SuspendReason.UserConfirmation _ -> payload instanceof ResumePayload.UserDecision;
            case SuspendReason.RemoteDelegation _ -> payload instanceof ResumePayload.RemoteResult;
            case SuspendReason.ScheduledWakeup _ -> payload instanceof ResumePayload.WakeupSignal;
            case SuspendReason.ExternalDataWait _ -> payload instanceof ResumePayload.DataReady;
            case SuspendReason.BrowserTakeover _ -> payload instanceof ResumePayload.BrowserTakeoverCompleted;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "恢复载荷类型不匹配: reason=%s, payload=%s".formatted(
                            reason.getClass().getSimpleName(), payload.getClass().getSimpleName()));
        }
    }

    /**
     * 将 ResumePayload 转换为人类可读的 Observation 文本。
     *
     * @param payload 恢复载荷
     * @return 格式化后的恢复描述文本
     */
    public String formatResumeObservation(ResumePayload payload) {
        return switch (payload) {
            case ResumePayload.WorkflowResult r ->
                    "工作流已完成: executionId=%s, status=%s, output=%s".formatted(
                            r.executionId(), r.status(), r.outputJson());
            case ResumePayload.UserDecision d ->
                    "用户确认结果: confirmationId=%s, approved=%s, reason=%s".formatted(
                            d.confirmationId(), d.approved(), d.reason());
            case ResumePayload.RemoteResult r ->
                    "远程 Agent 返回: remoteTaskId=%s, result=%s".formatted(
                            r.remoteTaskId(), r.resultJson());
            case ResumePayload.WakeupSignal s ->
                    "定时唤醒触发: actualWakeupAt=%s".formatted(s.actualWakeupAt());
            case ResumePayload.DataReady d ->
                    "外部数据就绪: dataSourceId=%s, data=%s".formatted(
                            d.dataSourceId(), d.dataLocationOrContent());
            case ResumePayload.BrowserTakeoverCompleted c ->
                    "浏览器人工接管完成: sessionId=%s, note=%s".formatted(
                            c.sessionId(), c.note() == null ? "" : c.note());
        };
    }

    /** 从 ChatResponse 中提取 prompt + completion token 总量。 */
    private int estimateTokens(ChatResponse chatResponse) {
        if (chatResponse == null) return 0;
        var metadata = chatResponse.getMetadata();
        if (metadata == null) return 0;
        var usage = metadata.getUsage();
        if (usage == null) return 0;
        int prompt = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completion = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return prompt + completion;
    }

    /** 从 ChatResponse 提取 finishReason（各 provider 字面值不同）。 */
    @Nullable
    private String extractFinishReason(ChatResponse chatResponse) {
        var result = chatResponse.getResult();
        if (result == null || result.getMetadata() == null) return null;
        return result.getMetadata().getFinishReason();
    }

    /** 判断 LLM 响应是否因 output token 耗尽而被截断。 */
    private boolean isResponseTruncated(@Nullable String finishReason) {
        if (finishReason == null) return false;
        // OpenAI: "length", Anthropic: "max_tokens"
        return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
    }

    /**
     * 记录 LLM 调用步骤到 Trace。
     *
     * <p>从 ChatResponse 元数据中提取真实的 Token 用量和完成原因，
     * 避免使用硬编码估算值。</p>
     *
     * @param traceContext 追踪上下文
     * @param stepIndex    步骤序号
     * @param timestamp    调用开始时间
     * @param duration     调用耗时
     * @param chatResponse LLM 原始响应（用于提取 Token 用量和完成原因）
     * @param providerId   LLM 提供商 ID
     * @param modelId      模型 ID
     */
    private void recordLlmStep(@Nullable TraceContext traceContext, int stepIndex,
                               Instant timestamp, Duration duration,
                               ChatResponse chatResponse,
                               String providerId, String modelId,
                               @Nullable Double temperature) {
        if (traceContext == null) return;
        try {
            // 从 ChatResponse 元数据提取真实 Token 用量
            int inputTokens = 0;
            int outputTokens = 0;
            var metadata = chatResponse.getMetadata();
            var usage = metadata.getUsage();
            if (usage != null) {
                inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            }

            // 兜底估算：当 Provider 未返回 usage 时，基于响应文本估算
            var result = chatResponse.getResult();
            String responseText = result != null && result.getOutput() != null
                    ? result.getOutput().getText() : null;
            if (outputTokens == 0 && responseText != null && !responseText.isEmpty()) {
                outputTokens = estimateTextTokens(responseText);
            }
            if (inputTokens == 0 && outputTokens > 0) {
                inputTokens = outputTokens * 4;
                log.debug("Token 兜底估算: inputTokens={} (基于 outputTokens={} × 4)",
                        inputTokens, outputTokens);
            }

            // 从 Generation 元数据提取完成原因
            String finishReason = null;
            if (result != null && result.getMetadata() != null) {
                finishReason = result.getMetadata().getFinishReason();
            }

            var step = new LlmCallStep(
                    stepIndex, timestamp, duration,
                    providerId, modelId,
                    config.getLoop().getLlmScene(),
                    inputTokens, outputTokens,
                    duration,
                    false,
                    temperature != null ? temperature : 0.0,
                    finishReason);
            traceRecorder.recordStep(traceContext, step);
        } catch (Exception e) {
            log.debug("Trace LLM 步骤记录失败: error={}", e.getMessage());
        }
    }

    /** 对纯文本做轻量 token 估算，主要用于日志和预算兜底。 */
    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    /** 生成日志预览文本，避免把长回答整段打到日志里。 */
    private String previewForLog(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    /** 判断当前请求是否带有多模态媒体输入。 */
    private boolean hasMultimodalContent(AgentRequest request) {
        return request.mediaContents() != null && !request.mediaContents().isEmpty();
    }

    /** 判断当前请求是否含有图片附件（需要 VISION Provider）。 */
    private boolean hasImageContent(AgentRequest request) {
        if (request.mediaContents() == null) return false;
        return request.mediaContents().stream()
                .anyMatch(mc -> mc.mimeType() != null && mc.mimeType().startsWith("image/"));
    }


    /**
     * 从消息列表中提取 MediaContent。
     *
     * <p>遍历 UserMessage 中的 Media 对象，转换为 MediaContent 列表。
     * 用于多模态路由回退场景（工具产生的媒体嵌入到 messages 中后需要重新提取）。</p>
     */
    @Override
    public List<MediaContent> extractMediaContentsFromMessages(List<Message> messages) {
        var result = new ArrayList<MediaContent>();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                for (var media : um.getMedia()) {
                    try {
                        Object rawData = media.getData();
                        byte[] data;
                        if (rawData instanceof Resource resource) {
                            data = resource.getContentAsByteArray();
                        } else if (rawData instanceof byte[] bytes) {
                            data = bytes;
                        } else {
                            log.warn("不支持的媒体数据类型: type={}", rawData.getClass().getSimpleName());
                            continue;
                        }
                        result.add(new MediaContent(
                                UUID.randomUUID().toString(),
                                media.getMimeType().toString(),
                                data,
                                "extracted",
                                data.length,
                                Map.of()));
                    } catch (Exception e) {
                        log.warn("从消息中提取媒体数据失败: error={}", e.getMessage());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 将 LlmResponse 适配为 Spring AI ChatResponse。
     *
     * <p>MultimodalRouter 返回 LlmResponse（content + token 用量），
     * 而 coreLoop 需要 ChatResponse（含 AssistantMessage），此方法完成适配。</p>
     *
     * @param llmResponse 多模态路由返回的 LLM 响应
     * @return 适配后的 ChatResponse
     */
    @Override
    public ChatResponse adaptToChatResponse(LlmResponse llmResponse) {
        var assistantMessage = new AssistantMessage(llmResponse.content());
        var generation = new Generation(assistantMessage);
        return new ChatResponse(List.of(generation));
    }

    /**
     * 将完整消息列表序列化为文本，供多模态路由使用。
     *
     * <p>当前统一委托给 {@link ProviderMessageBuilder#serializeForMultimodal(List)}，
     * 不再保留 ReactAgentLoop 内部的旧版 fallback 串行化逻辑。</p>
     *
     * @param messages Spring AI 消息列表
     * @return 包含完整对话上下文的文本
     */
    @Override
    public String buildConversationContextText(List<Message> messages) {
        return providerMessageBuilder.serializeForMultimodal(messages);
    }

    // ===== 流式 LLM Trace 记录 =====

    /**
     * 记录流式 LLM 调用步骤到 Trace。
     *
     * <p>从 ChatResponse 元数据提取真实 Token 用量和完成原因，
     * 与 {@link #recordLlmStep} 保持一致的数据提取逻辑。</p>
     */
    @Override
    public void recordStreamingLlmStep(@Nullable TraceContext traceContext,
                                        Instant startTime, String providerId,
                                        String modelId, String scene,
                                        ChatResponse chatResponse,
                                        @Nullable Exception error) {
        if (traceRecorder == null || traceContext == null) return;
        Instant end = Instant.now();
        Duration d = Duration.between(startTime, end);

        // 从 ChatResponse 元数据提取真实 Token 用量
        int inputTokens = 0;
        int outputTokens = 0;
        if (chatResponse != null) {
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                inputTokens = (int) usage.getPromptTokens();
                outputTokens = error != null ? 0 : (int) usage.getCompletionTokens();
            }

            // 兜底估算：当 Provider 未返回 usage 时，基于响应文本估算
            if (error == null) {
                String responseText = chatResponse.getResult().getOutput().getText();
                // outputTokens 兜底：基于响应文本估算
                if (outputTokens == 0 && responseText != null && !responseText.isEmpty()) {
                    outputTokens = estimateTextTokens(responseText);
                }
                // inputTokens 兜底：流式调用中 Provider 经常不返回 promptTokens，
                // 基于 outputTokens 按经验比例估算（输入通常是输出的 3-5 倍）
                if (inputTokens == 0 && outputTokens > 0) {
                    inputTokens = outputTokens * 4;
                    log.debug("Token 兜底估算: inputTokens={} (基于 outputTokens={} × 4)",
                            inputTokens, outputTokens);
                }
            }
        }

        // 从 Generation 元数据提取完成原因
        String finishReason;
        if (error != null) {
            finishReason = "error: " + error.getMessage();
        } else if (chatResponse != null) {
            var resultMetadata = chatResponse.getResult().getMetadata();
            finishReason = resultMetadata.getFinishReason();
        } else {
            finishReason = null;
        }

        int stepIndex = traceContext.steps() != null ? traceContext.steps().size() : 0;
        var step = new LlmCallStep(
                stepIndex, end, d,
                providerId != null ? providerId : DEFAULT_MODEL_ID,
                modelId != null ? modelId : DEFAULT_MODEL_ID,
                scene != null ? scene : "unknown",
                inputTokens, outputTokens, d,
                false,  // cacheHit — Spring AI 不提供
                0.0,    // temperature — ChatResponse 不包含请求侧参数
                finishReason);
        traceRecorder.recordStep(traceContext, step);
    }

    // ===== SSE 推理事件 =====

    /**
     * 将 ReactStep 转换为 REASONING SSE 事件并推送。
     *
     * <p>仅在流式模式下生效（loopContext 包含 SSE 上下文时）。
     * 使用 switch 表达式穷举 6 种步骤类型，生成对应的事件标题和描述。</p>
     *
     * @param step        刚追加的 ReactStep
     * @param stepIndex   步骤索引
     * @param state       当前 Agent 状态
     * @param loopContext 循环上下文（含 SSE 管理器和流 ID）
     */
    void pushReactStepEvent(ReactStep step, int stepIndex,
                            ReactAgentState state, AgentLoopContext loopContext) {
        var sseManager = loopContext.getSseManager();
        var streamId = loopContext.getStreamId();
        var turnId = loopContext.getTurnId();
        if (sseManager == null || streamId == null || turnId == null) return;

        var info = switch (step) {
            case ReactStep.Progress(var content) -> new String[]{
                    "PROGRESS", "执行进度",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content,
                    null
            };
            case ReactStep.Thought(var content) -> new String[]{
                    "THOUGHT", "推理思考",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content,
                    null
            };
            case ReactStep.ToolCall toolCall -> {
                // 用户可读的显示名称，优先 toolName，回退到 toolId
                String display = toolCall.toolName() != null ? toolCall.toolName() : toolCall.toolId();
                yield new String[]{
                    "TOOL_CALL", "调用工具: " + display,
                    "正在执行工具 " + display,
                    display,  // SSE toolName 字段传显示名称
                    toolCall.toolId()    // SSE toolId 字段传技术标识
                };
            }
            case ReactStep.Observation observation -> {
                String display = observation.toolName() != null
                        ? observation.toolName()
                        : observation.toolId();
                yield new String[]{
                    "OBSERVATION", (observation.success() ? "工具返回: " : "工具失败: ") + display,
                    observation.output().length() > 100
                            ? observation.output().substring(0, 100) + "..."
                            : observation.output(),
                    display,
                    observation.toolId()
                };
            }
            case ReactStep.Answer(var content) -> new String[]{
                    "ANSWER", "生成回答",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content,
                    null
            };
            case ReactStep.Suspend(var reason, var suspendedAt, var idx) -> new String[]{
                    "SUSPEND", "Agent 挂起",
                    formatSuspendReason(reason),
                    null
            };
            case ReactStep.Resume(var payload, var resumedAt, var duration) -> new String[]{
                    "RESUME", "Agent 恢复",
                    "挂起时长: " + duration.toMillis() + "ms",
                    null
            };
            case ReactStep.Reflect(var content, var trigger) -> new String[]{
                    "REFLECT", "执行回顾",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content,
                    null
            };
        };

        var extra = new HashMap<String, Object>();
        extra.put("stepIndex", stepIndex);
        // info[4] 存在时为 toolId（技术标识），传入 extra 供前端调试使用
        if (info.length > 4 && info[4] != null) {
            extra.put("toolId", info[4]);
        }
        loopContext.markFirstReasoningEvent(Instant.now());
        sendReasoningEvent(sseManager, streamId, state.sessionId(), turnId,
                info[0], info[1], info[2], info[3], extra, loopContext.getEventBuffer());
    }

    /** 发送 REASONING SSE 事件（支持事件缓冲区）。 */
    @Override
    public void sendReasoningEvent(SseSessionManager sseManager, String streamId,
                                    String sessionId, String turnId,
                                    String type, String title, String description,
                                    @Nullable String toolName, Map<String, Object> extra,
                                    @Nullable SseEventBuffer eventBuffer) {
        try {
            var eventDetail = new HashMap<String, Object>();
            eventDetail.put("id", UUID.randomUUID().toString());
            eventDetail.put("type", type);
            eventDetail.put("title", title);
            eventDetail.put("description", description);
            eventDetail.put("createdAt", Instant.now().toString());
            eventDetail.put("extra", extra != null ? extra : Map.of());
            if (toolName != null) {
                eventDetail.put("toolName", toolName);
            }
            var eventPayload = new HashMap<String, Object>();
            eventPayload.put("sessionId", sessionId);
            eventPayload.put("turnId", turnId);
            eventPayload.put("event", eventDetail);
            if (eventBuffer != null) {
                eventBuffer.offer(SseEventType.REASONING, eventPayload);
            } else {
                sseManager.sendEvent(streamId, SseEventType.REASONING, eventPayload);
            }
        } catch (Exception e) {
            log.debug("发送 reasoning 事件失败: type={}, error={}", type, e.getMessage());
        }
    }

    /** 增强系统提示词（注入流式约束）。 */
    @Override
    public String enhanceSystemPromptForStreaming(String systemText) {
        return contextAssembler.enhanceSystemPromptForStreaming(systemText);
    }

    // ===== 提示词辅助 =====

    /**
     * 调试日志 — 打印发送给 LLM 的完整消息列表。
     *
     * <p>记录最终发送的所有 Message（包括 SystemMessage、UserMessage、
     * 历史 AssistantMessage/ToolResponseMessage），确保日志与实际请求一致。</p>
     */
    @Override
    public void logLlmPromptIfEnabled(String scene, List<Message> messages,
                                       @Nullable List<ToolCallback> toolCallbacks) {
        if (config == null || config.getDebug() == null || !config.getDebug().isLogLlmPrompts()) {
            return;
        }
        List<String> toolNames = new ArrayList<>();
        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            toolNames = toolCallbacks.stream()
                    .filter(Objects::nonNull)
                    .map(cb -> cb.getToolDefinition().name())
                    .distinct()
                    .collect(Collectors.toList());
        }
        var sb = new StringBuilder();
        sb.append(ContextMessageFormatter.serializeForDebug(messages));
        log.info("""
                ========== LLM PROMPT ==========
                scene={} traceId=react
                tools={}
                messageCount={}
                -------- MESSAGES --------
                {}========= END PROMPT ==========""",
                scene, toolNames, messages.size(), sb);
    }

}
