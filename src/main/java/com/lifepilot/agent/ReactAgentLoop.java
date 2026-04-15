package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.CallbackHelper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.*;
import com.lifepilot.agent.execution.ExecutionCompletionPolicy;
import com.lifepilot.agent.execution.ReflectContentBuilder;
import com.lifepilot.agent.execution.ToolExecutionCoordinator;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.suspend.event.ScheduledWakeupEvent;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.sse.SseEventBuffer;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.memory.workspace.TaskStateItem;
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

    // ===== 可选依赖（Skill 工具激活） =====
    @Nullable private final com.lifepilot.skill.registry.SkillRegistry skillRegistry;

    // ===== 可选依赖（MCP 工具激活） =====
    @Nullable private final DynamicToolRegistry toolRegistry;

    // ===== 可选依赖（即时经验补丁） =====
    @Nullable private final com.lifepilot.memory.experience.ExperienceSummarizer experienceSummarizer;

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
            @Nullable com.lifepilot.skill.registry.SkillRegistry skillRegistry,
            @Nullable DynamicToolRegistry toolRegistry,
            @Nullable com.lifepilot.memory.semantic.SemanticMemory semanticMemory,
            @Nullable com.lifepilot.memory.experience.ExperienceSummarizer experienceSummarizer) {
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
                semanticMemory
        );
        this.compactionEngine = compactionEngine;
        this.traceRecorder = traceRecorder;
        this.multimodalRouter = multimodalRouter;
        this.eventPublisher = eventPublisher;
        this.proceduralMemory = proceduralMemory;
        this.intentMatcher = intentMatcher;
        this.workspaceService = workspaceService;
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
        this.experienceSummarizer = experienceSummarizer;
        this.suspendScheduler = sharedScheduler.cleanup();
    }

    /** 获取多模态路由器（预留：多模态流式请求）。 */
    @Nullable
    public MultimodalRouter getMultimodalRouter() {
        return multimodalRouter;
    }

    ProviderMessageBuilder.BuildResult buildProviderMessages(AssembledContext ctx, ReactAgentState state) {
        return providerMessageBuilder.build(ctx, state);
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
                cachedToolCallbacks = agentToolProvider.getToolCallbacks(state, loopContext.getStreamId());
            }
            var toolCallbacks = cachedToolCallbacks;

            log.debug("ReAct 迭代开始: traceId={}, iteration={}, stepCount={}, toolCount={}",
                    state.traceId(), iteration, state.stepCount(), toolCallbacks.size());

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
                chatResponse = callback.callLlm(effectiveRequest, messages, toolCallbacks, traceContext);
            } catch (Exception e) {
                log.error("LLM 调用异常: traceId={}, iteration={}, error={}",
                        state.traceId(), iteration, e.getMessage());
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

            // 7. 判断是否有 tool call 请求
            if (assistantMessage.hasToolCalls()) {
                // === ReAct: Action 阶段 — 处理 tool call ===
                var toolCalls = assistantMessage.getToolCalls();

                // 如果 LLM 同时返回了文本（思考内容），记录为 Thought
                String thoughtText = assistantMessage.getText();
                if (thoughtText != null && !thoughtText.isBlank()) {
                    state = state.appendStep(new ReactStep.Thought(thoughtText));
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                }

                int preExecStepCount = state.stepCount();
                state = toolExecutionCoordinator.executeBatch(
                        state,
                        toolCalls,
                        toolCallbacks,
                        traceContext,
                        cancellationToken,
                        loopContext,
                        this::appendAndPublishStep);

                // ★ Skill 工具激活 — 检测 file.read 返回的 _skillIds 并激活对应工具，同时提取指南内容
                var activation = detectSkillToolActivation(state, preExecStepCount);
                if (activation.hasActivation()) {
                    state = state.withActivatedToolIds(activation.toolIds());
                    cachedToolCallbacks = null;
                    log.info("Skill 工具已激活: traceId={}, skills={}, totalActivated={}",
                            state.traceId(), activation.toolIds(),
                            state.activatedToolIds() != null ? state.activatedToolIds().size() : 0);
                }
                if (activation.skillContent() != null) {
                    state = state.appendSkillContent(activation.skillContent());
                    cachedContext = null;  // Skill 指南已注入 state，需重建系统提示词
                }

                // ★ 通用挂起检测 — 仅检查 suspended 布尔标志，不引用具体工具名或 SuspendReason 子类型
                if (state.suspended()) {
                    log.info("Agent 进入挂起态: traceId={}, reason={}", state.traceId(), state.suspendReason());
                    state = state.appendStep(new ReactStep.Suspend(
                            state.suspendReason(), Instant.now(), state.stepCount()));
                    pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                    // 冻结 Budget elapsed 到当前时间点
                    state = state.toBuilder()
                            .budget(state.budget().withElapsed(Duration.between(loopStart, Instant.now())))
                            .build();
                }

                // ★ 外层循环挂起检测 — 挂起后跳出主迭代循环
                if (state.suspended()) break;
                if (cancellationToken.isCancelled()) break;

                // ★ 执行回顾注入点 — 工具执行完毕且未挂起时评估是否需要反思
                var maybeReflect = evaluateReflectionTrigger(state, iteration);
                if (maybeReflect != null) {
                    state = appendAndPublishStep(state, maybeReflect, loopContext);
                    persistReflectToWorkspace(state, iteration);
                    // 即时经验补丁：工具失败反思时同步写入经验，当前轮次即可被检索
                    if (experienceSummarizer != null) {
                        experienceSummarizer.quickLearn(state, maybeReflect.content(), maybeReflect.trigger());
                    }
                    cachedContext = null;
                    cachedToolCallbacks = null;
                }

                // 扣减 Token 预算
                state = state.toBuilder()
                        .budget(state.budget().deductTokens(responseTokens))
                        .build();
                consecutiveFailures = 0;

            } else {
                // === ReAct: Answer 阶段 — 纯文本响应 ===
                String content = assistantMessage.getText();
                if (content != null && !content.isBlank()) {
                    // 截断的文本不能当完整答案 — 作为 Thought 记录，让 LLM 在下一轮继续
                    if (truncated) {
                        state = state.appendStep(new ReactStep.Thought(content));
                        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                        state = state.toBuilder()
                                .budget(state.budget().deductTokens(responseTokens))
                                .build();
                        consecutiveFailures = 0;
                    } else {
                    var completionEvaluation = completionPolicy.evaluate(request, state, content);
                    if (completionEvaluation.disposition() == ExecutionCompletionPolicy.CompletionDisposition.EXPLICIT_TERMINAL) {
                        String visibleContent = completionEvaluation.userVisibleContent() != null
                                ? completionEvaluation.userVisibleContent()
                                : content;
                        CompletionReason completionReason = completionEvaluation.completionReason();
                        state = appendAndPublishStep(state, new ReactStep.Answer(visibleContent), loopContext);
                        state = state.toBuilder()
                                .done(true)
                                .finalOutput(visibleContent)
                                .terminationReason(completionEvaluation.terminationReason())
                                .completionReason(completionReason)
                                .budget(state.budget().deductTokens(responseTokens))
                                .build();

                        log.info("ReAct 循环完成: traceId={}, iterations={}, stepCount={}, tokensUsed={}, taskMode={}, completionReason={}, preview={}",
                                state.traceId(), iteration + 1, state.stepCount(),
                                state.budget().tokensUsed(), state.taskMode(), completionReason,
                                previewForLog(visibleContent));
                    } else if (completionEvaluation.disposition() == ExecutionCompletionPolicy.CompletionDisposition.SUSPEND_FOR_USER_INPUT) {
                        String visibleContent = completionEvaluation.userVisibleContent() != null
                                ? completionEvaluation.userVisibleContent()
                                : content;
                        String suspendPrompt = completionEvaluation.suspendPrompt() != null
                                ? completionEvaluation.suspendPrompt()
                                : visibleContent;
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
                        break;
                    } else if (completionEvaluation.disposition() == ExecutionCompletionPolicy.CompletionDisposition.REJECT_IMPLICIT_TERMINATION) {
                        int rejectCount = state.earlyStopRejectCount() + 1;
                        log.warn("疑似提前结束已拒绝: traceId={}, iteration={}, rejectCount={}, preview={}",
                                state.traceId(), iteration, rejectCount, previewForLog(content));
                        if (rejectCount >= config.getLoop().getMaxEarlyStopRejects()) {
                            state = DegradedResponseBuilder.terminateWithReason(
                                    state.toBuilder()
                                            .finalOutput(content)
                                            .build(),
                                    "多步执行任务未满足结束协议，系统已拒绝提前结束",
                                    CompletionReason.EARLY_STOP_REJECTED);
                            break;
                        }
                        state = state.appendStep(new ReactStep.Thought(
                                "当前回复缺少终态控制信息。如果任务已经完成，请在自然语言正文后追加 `<completion_control>done</completion_control>`；如果明确阻塞，请追加 `<completion_control>blocked</completion_control>`；如果还要继续，就直接调用下一步工具。"));
                        pushReactStepEvent(state.steps().getLast(), state.stepCount() - 1, state, loopContext);
                        state = state.toBuilder()
                                .earlyStopRejectCount(rejectCount)
                                .budget(state.budget().deductTokens(responseTokens))
                                .build();
                        consecutiveFailures = 0;
                        cachedContext = null;
                        cachedToolCallbacks = null;
                    } else {
                        String visibleContent = completionEvaluation.userVisibleContent() != null
                                ? completionEvaluation.userVisibleContent()
                                : content;
                        state = appendAndPublishStep(state, new ReactStep.Answer(visibleContent), loopContext);
                        state = state.toBuilder()
                                .done(true)
                                .finalOutput(visibleContent)
                                .completionReason(CompletionReason.DIRECT_ANSWER)
                                .budget(state.budget().deductTokens(responseTokens))
                                .build();

                        log.info("ReAct 循环完成: traceId={}, iterations={}, stepCount={}, tokensUsed={}, taskMode={}, completionReason={}, preview={}",
                                state.traceId(), iteration + 1, state.stepCount(),
                                state.budget().tokensUsed(), state.taskMode(), CompletionReason.DIRECT_ANSWER,
                                previewForLog(visibleContent));
                    }
                    } // end !truncated
                } else {
                    // LLM 返回空内容 — 记录 Observation 而非静默跳过
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
                        break;
                    }
                }
            }

            // 8. 迭代完成后更新步数，并在进入下一轮前再次执行预算检查
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

        return state;
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

    /**
     * Skill 激活检测结果 — 包含需要激活的工具 ID 和 Skill 指南内容。
     */
    private record SkillActivationResult(Set<String> toolIds, @Nullable String skillContent) {
        boolean hasActivation() {
            return !toolIds.isEmpty();
        }
    }

    /**
     * 检测 file.read 工具结果中的 _skillIds 字段，合并所有相关 Skill 的 suggestedTools，
     * 同时提取 Skill 指南内容用于注入系统提示词。
     *
     * @param state 当前状态（包含新增的 Observation 步骤）
     * @param fromStepIndex 扫描起始步骤索引
     * @return 激活结果，包含工具 ID 集合和 Skill 指南内容
     */
    private SkillActivationResult detectSkillToolActivation(ReactAgentState state, int fromStepIndex) {
        if (config.getCoreToolIds().isEmpty()) {
            return new SkillActivationResult(Set.of(), null);
        }
        if (skillRegistry == null && toolRegistry == null) {
            return new SkillActivationResult(Set.of(), null);
        }
        Set<String> toolIds = new LinkedHashSet<>();
        String skillContent = null;
        for (int i = fromStepIndex; i < state.steps().size(); i++) {
            if (!(state.steps().get(i) instanceof ReactStep.Observation obs)) continue;
            if (!obs.success() || obs.output() == null) continue;

            // 尝试解析 _skillIds 和 content 字段
            var parsed = extractSkillData(obs.output());
            if (parsed == null) continue;

            // 提取 Skill 指南内容
            if (parsed.content() != null && !parsed.content().isBlank()) {
                skillContent = parsed.content();
            }

            for (String skillId : parsed.skillIds()) {
                if (skillId.startsWith("mcp:")) {
                    // MCP server — 从 registry 获取该 server 的所有工具 ID
                    String serverName = skillId.substring(4);
                    if (toolRegistry != null) {
                        toolRegistry.getToolsByServer(serverName)
                                .forEach(tool -> toolIds.add(tool.id()));
                    }
                } else {
                    // 内置 Skill — 从 SkillRegistry 获取 suggestedTools
                    if (skillRegistry != null) {
                        skillRegistry.find(skillId).ifPresent(def -> toolIds.addAll(def.suggestedTools()));
                    }
                }
            }
        }
        return new SkillActivationResult(toolIds, skillContent);
    }

    /** Skill 数据解析结果。 */
    private record SkillData(List<String> skillIds, @Nullable String content) {}

    /** 从工具输出 JSON 中提取 _skillIds 列表和 content 字段。 */
    @SuppressWarnings("unchecked")
    @Nullable
    private SkillData extractSkillData(String output) {
        try {
            var data = objectMapper.readValue(output, Map.class);
            Object raw = data.get("_skillIds");
            if (!(raw instanceof List<?> list)) {
                return null;
            }
            var skillIds = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
            String content = data.get("content") instanceof String s ? s : null;
            return new SkillData(skillIds, content);
        } catch (Exception ignored) {
            // 非 JSON 或不含 _skillIds — 正常，忽略
            return null;
        }
    }
}
