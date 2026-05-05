package com.lifepilot.agent.callback;

import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.sse.SseEventBuffer;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.StreamingLlmResponse;
import com.lifepilot.llm.adapter.ProviderChatOptionsFactory;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.observability.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 流式迭代回调 — runStreaming() 使用。
 *
 * <p>通过 ChatModel.stream(Prompt) 流式调用 LLM，逐 token 发送 SSE TOKEN 事件。
 * 当 LLM 返回 tool call 请求时，收集完整响应后构造含 tool call 的 ChatResponse
 * 供 coreLoop 手动执行工具。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class StreamingCallback implements IterationCallback {

    private static final Logger log = LoggerFactory.getLogger(StreamingCallback.class);
    private static final Duration TOKEN_BATCH_MAX_DELAY = Duration.ofMillis(24);
    private static final int TOKEN_BATCH_MAX_CHARS = 96;

    private final AgentConfigProperties config;
    private final GenerationRouter generationRouter;
    @Nullable private final MultimodalRouter multimodalRouter;
    private final CallbackHelper helper;
    private final CancellationToken cancellationToken;
    @Nullable private final AgentLoopContext loopContext;

    private final SseSessionManager sseManager;
    @Nullable private final SseEventBuffer eventBuffer;
    private final String streamId;
    private final String sessionId;
    private final String turnId;
    private final AgentRequest request;

    private String providerId = IterationCallback.DEFAULT_MODEL_ID;
    private String modelId = IterationCallback.DEFAULT_MODEL_ID;
    @Nullable private Exception streamingError;
    @Nullable private String finalContent;
    /**
     * 累加本次调用产生的推理过程文本 — 由 ReasoningChunk 流式增量拼接而成。
     *
     * <p>DeepSeek V4 / Qwen3 等推理模型多轮契约要求带 tool_calls 的 assistant 消息
     * 必须回传上一轮的 reasoning_content；该字段持有完整原文供 ReactAgentLoop 写入
     * {@link com.lifepilot.agent.model.ReactStep.ToolCall#reasoningContent()}。
     */
    private final StringBuilder reasoningContentBuilder = new StringBuilder();
    private final StringBuilder pendingTokenBatch = new StringBuilder();
    @Nullable private Instant tokenBatchOpenedAt;
    private int nextTokenIndex;

    /**
     * 每次调用 {@link #callLlm} 入口必须重置这些状态。
     *
     * <p>StreamingCallback 在整个 turn 复用单实例；若不清理，某次流式异常会残留在字段中，
     * 导致后续重试或收尾总结被同一个旧异常污染，表现为“明明重试了但还是立即失败”。</p>
     */
    private void resetPerCallState() {
        // 单次调用累积内容必须隔离
        reasoningContentBuilder.setLength(0);
        finalContent = null;
        // 关键：流式异常不能跨调用残留，否则会误判“消费完成仍有异常”
        streamingError = null;
        // token 合批缓冲同样按调用隔离（避免跨调用把前一轮尾巴刷到下一轮）
        clearPendingTokenBatch();
        // token index 允许跨调用递增（前端按 index 仅用于顺序，不要求从 0 开始）
    }

    private void throwAndClearStreamingErrorIfPresent(String stage) {
        if (this.streamingError == null) {
            return;
        }
        Exception err = this.streamingError;
        // 先清理再抛出，避免上层 catch 后继续复用 callback 时被旧错误污染
        this.streamingError = null;
        log.warn("{}完成但存在未传播的异常，重新抛出: error={}", stage, err.getMessage());
        throw err instanceof RuntimeException re ? re : new RuntimeException(err);
    }

    public StreamingCallback(AgentConfigProperties config,
                             GenerationRouter generationRouter,
                             @Nullable MultimodalRouter multimodalRouter,
                             CallbackHelper helper,
                             CancellationToken cancellationToken,
                             @Nullable AgentLoopContext loopContext,
                             SseSessionManager sseManager,
                             String streamId,
                             String sessionId,
                             String turnId,
                             AgentRequest request) {
        this.config = config;
        this.generationRouter = generationRouter;
        this.multimodalRouter = multimodalRouter;
        this.helper = helper;
        this.cancellationToken = cancellationToken;
        this.loopContext = loopContext;
        this.sseManager = sseManager;
        this.eventBuffer = loopContext != null ? loopContext.getEventBuffer() : null;
        this.streamId = streamId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.request = request;
    }

    @Override
    public ChatResponse callLlm(AgentRequest req,
                                List<Message> messages,
                                List<ToolCallback> toolCallbacks,
                                @Nullable TraceContext traceContext) {
        String scene = config.getLoop().getLlmScene();

        // StreamingCallback 在整个 turn 复用单实例（AgentOrchestrator 持有），
        // 每次 LLM 调用的错误、正文和 reasoning 都必须独立，避免前一轮异常污染重试或收尾总结。
        resetPerCallState();

        // 动态路由：仅当 UserMessage 含真正的多模态媒体（image / audio / video）时走多模态路径。
        // 文档类附件（pdf / docx / md / txt / csv 等）通过 file.read(attachmentId=...) 按需解析，
        // 不占用多模态通道 — 否则会导致 toolCallbacks 被丢弃（MultimodalRequest 架构上不承载 tools）。
        boolean messagesHaveMultimodalMedia = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .flatMap(um -> um.getMedia().stream())
                .anyMatch(StreamingCallback::isMultimodalMedia);

        // 多模态流式路由
        if (messagesHaveMultimodalMedia && multimodalRouter != null) {
            return callMultimodalStreaming(req, messages, toolCallbacks, scene, traceContext);
        }

        if (messagesHaveMultimodalMedia) {
            log.warn("消息包含媒体内容但 MultimodalRouter 不可用，回退到纯文本路由");
        }

        return callTextStreaming(req, messages, toolCallbacks, scene, traceContext);
    }

    /** 判断 Media 是否属于真正的多模态类型（image / audio / video）。 */
    private static boolean isMultimodalMedia(org.springframework.ai.content.Media media) {
        var mime = media.getMimeType();
        if (mime == null) {
            return false;
        }
        String type = mime.getType();
        return "image".equalsIgnoreCase(type)
                || "audio".equalsIgnoreCase(type)
                || "video".equalsIgnoreCase(type);
    }

    /** 多模态流式路由。 */
    private ChatResponse callMultimodalStreaming(AgentRequest req, List<Message> messages,
                                                 List<ToolCallback> toolCallbacks,
                                                 String scene, @Nullable TraceContext traceContext) {
        // 清空上一次迭代可能残留的 token 缓冲
        clearPendingTokenBatch();
        var mediaContents = req.mediaContents() != null && !req.mediaContents().isEmpty()
                ? req.mediaContents()
                : helper.extractMediaContentsFromMessages(messages);
        String conversationText = helper.buildConversationContextText(messages);
        var multimodalRequest = new MultimodalRequest(
                scene, conversationText,
                mediaContents, null, req.preferredProvider(), null,
                toolCallbacks);

        // 多模态路径调试日志 — 记录工具列表以便排查工具透传问题
        helper.logLlmPromptIfEnabled(scene, messages, toolCallbacks);

        StreamingLlmResponse streamingResponse = multimodalRouter.streamWithInfo(multimodalRequest);
        this.providerId = streamingResponse.providerId();
        this.modelId = streamingResponse.modelId();
        log.debug("流式多模态路由开始: scene={}, provider={}, model={}",
                scene, this.providerId, this.modelId);

        Instant callStart = Instant.now();
        markModelStreamStarted(callStart);
        var contentBuilder = new StringBuilder();
        final Instant[] firstTokenTime = {null};

        try {
            // 多模态路径无 reasoning，事件流仅含 ContentChunk（由 MultimodalRouter 包装）
            streamingResponse.events()
                    .takeWhile(ev -> !cancellationToken.isCancelled()
                            && sseManager.getEmitter(streamId) != null)
                    .doOnNext(ev -> {
                        switch (ev) {
                            case com.lifepilot.llm.stream.ContentChunk c -> {
                                contentBuilder.append(c.delta());
                                Instant now = Instant.now();
                                if (firstTokenTime[0] == null) {
                                    firstTokenTime[0] = now;
                                }
                                markFirstModelToken(now);
                                pushTokenToSse(c.delta());
                            }
                            case com.lifepilot.llm.stream.ReasoningChunk r -> pushReasoningToSse(r.delta());
                            case com.lifepilot.llm.stream.ToolCallDelta ignored -> { /* 多模态路径不解析 tool call */ }
                            case com.lifepilot.llm.stream.UsageEvent ignored -> { /* 多模态路径不记录 usage */ }
                            case com.lifepilot.llm.stream.DoneEvent ignored -> { /* 流结束信号，无需特殊处理 */ }
                            case com.lifepilot.llm.stream.ErrorEvent err ->
                                    this.streamingError = new RuntimeException(err.code() + ": " + err.message());
                        }
                    })
                    .doOnError(e -> {
                        log.warn("流式多模态调用异常: scene={}, error={}", scene, describeProviderError(e));
                        this.streamingError = e instanceof Exception ex ? ex : new RuntimeException(e);
                    })
                    .blockLast();
        } catch (Exception e) {
            // doOnError 已捕获异常到 streamingError，blockLast 会重新抛出同一异常；
            // 统一由下方 streamingError 检查处理，避免双重抛出
            if (this.streamingError == null) {
                this.streamingError = e;
            }
            log.debug("多模态流式 blockLast 异常（已捕获到 streamingError）: {}", e.getMessage());
        }

        if (cancellationToken.isCancelled()) {
            log.debug("多模态流式消费因取消信号停止: streamId={}", streamId);
        } else if (sseManager.getEmitter(streamId) == null) {
            log.debug("多模态流式消费因 SSE 连接断开停止: streamId={}", streamId);
        }
        flushPendingTokenBatch();
        throwAndClearStreamingErrorIfPresent("多模态流式消费");

        String collectedContent = contentBuilder.toString();
        this.finalContent = collectedContent;

        Instant callEnd = Instant.now();
        long ttftMs = firstTokenTime[0] != null ? Duration.between(callStart, firstTokenTime[0]).toMillis() : -1;
        long totalMs = Duration.between(callStart, callEnd).toMillis();
        var timings = streamingTimings();
        log.info("多模态流式调用完成: scene={}, provider={}, model={}, ttft={}ms, total={}ms, " +
                        "requestToFirstReasoning={}ms, requestToFirstTokenSse={}ms, modelStreamStartToFirstToken={}ms, contentLength={}",
                scene, this.providerId, this.modelId, ttftMs, totalMs,
                timingOrDefault(timings, "requestReceivedToFirstReasoningEventMs"),
                timingOrDefault(timings, "requestReceivedToFirstTokenSseMs"),
                timingOrDefault(timings, "modelStreamStartToFirstTokenMs"),
                collectedContent.length());

        ChatResponse chatResponse = helper.adaptToChatResponse(
                LlmResponse.simple(collectedContent, 0, 0, this.providerId, this.modelId, 0));
        helper.recordStreamingLlmStep(traceContext, callStart, providerId, modelId, scene, chatResponse, null);
        return chatResponse;
    }

    /** 纯文本流式路由。 */
    private ChatResponse callTextStreaming(AgentRequest req, List<Message> messages,
                                           List<ToolCallback> toolCallbacks, String scene,
                                           @Nullable TraceContext traceContext) {
        // 清空上一次迭代可能残留的 token 缓冲
        clearPendingTokenBatch();
        String preferredProviderId = request.preferredProvider();

        // 提取 system 文本，通过 CallbackHelper 集中增强（流式约束）
        // A2UI 通过 ui.emit tool call 提交组件树，不再使用文本标签解析
        String systemText = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .findFirst().orElse("");

        String streamingSystemPrompt = helper.enhanceSystemPromptForStreaming(systemText);

        // 先尝试用 ChatModel 做一次非流式调用检测 tool call
        var chatModelInfo = generationRouter.getChatModelWithInfo(scene, preferredProviderId, null);
        this.providerId = chatModelInfo.serviceId();
        this.modelId = chatModelInfo.modelName();

        var chatOptions = ProviderChatOptionsFactory.create(
                new ProviderChatOptionsFactory.ProviderDescriptor(
                        chatModelInfo.baseAdapter(),
                        chatModelInfo.apiUrl()
                ),
                chatModelInfo.chatModel(),
                chatModelInfo.modelName(),
                req.temperature(),
                toolCallbacks,
                false,
                true,
                null
        );

        // 替换 system message 为增强版
        var enhancedMessages = new ArrayList<>(messages);
        if (!enhancedMessages.isEmpty() && enhancedMessages.getFirst() instanceof SystemMessage) {
            enhancedMessages.set(0, new SystemMessage(
                    streamingSystemPrompt != null ? streamingSystemPrompt : systemText));
        }

        // 调试日志
        helper.logLlmPromptIfEnabled(scene, enhancedMessages, toolCallbacks);

        var prompt = new Prompt(enhancedMessages, chatOptions);

        // 流式能力检查与分支
        if (!chatModelInfo.supportsStreaming()) {
            log.info("Provider 不支持流式调用，降级为非流式: provider={}, model={}",
                    chatModelInfo.serviceId(), chatModelInfo.modelName());
            return callLlmNonStreaming(chatModelInfo, prompt, traceContext);
        }

        // 真正的流式调用路径
        Instant callStart = Instant.now();
        markModelStreamStarted(callStart);
        String scene2 = config.getLoop().getLlmScene();

        var contentBuilder = new StringBuilder();
        var toolCallAggregator = new StreamingToolCallAggregator();
        final Instant[] firstTokenTime = {null};
        final boolean[] toolCallPreviewSent = {false};
        // 累加流式 chunk 中的 Token 用量（部分 Provider 仅在最后一个 chunk 返回完整 usage）
        final long[] accumulatedPromptTokens = {0};
        final long[] accumulatedCompletionTokens = {0};
        // 记录 prompt cache 命中 token 数 — OpenAI / DashScope / Anthropic 自动缓存命中时返回该字段
        final long[] accumulatedCachedTokens = {0};

        // 升级到 LlmStreamEvent 流：通过 GenerationRouter.streamWithInfo 拿事件流，
        // 按 sealed pattern matching 分派 ContentChunk / ReasoningChunk / ToolCallDelta / UsageEvent / Done / Error
        StreamingLlmResponse streamingResponse = generationRouter.streamWithInfo(
                scene2, preferredProviderId, prompt, toolCallbacks);
        // providerId / modelId 已由前面 chatModelInfo 路径设置；streamWithInfo 路径返回的元信息保持一致

        try {
            streamingResponse.events()
                    .takeWhile(ev -> !cancellationToken.isCancelled()
                            && sseManager.getEmitter(streamId) != null)
                    .doOnNext(ev -> {
                        try {
                            switch (ev) {
                                case com.lifepilot.llm.stream.ContentChunk c -> {
                                    String text = c.delta();
                                    if (text != null && !text.isEmpty()) {
                                        contentBuilder.append(text);
                                        Instant now = Instant.now();
                                        if (firstTokenTime[0] == null) {
                                            firstTokenTime[0] = now;
                                        }
                                        markFirstModelToken(now);
                                        pushTokenToSse(text);
                                    }
                                }
                                case com.lifepilot.llm.stream.ReasoningChunk r -> {
                                    if (r.delta() != null) {
                                        reasoningContentBuilder.append(r.delta());
                                    }
                                    pushReasoningToSse(r.delta());
                                }
                                case com.lifepilot.llm.stream.ToolCallDelta tcd -> {
                                    emitToolCallPreviewForDelta(tcd, toolCallPreviewSent);
                                    toolCallAggregator.merge(tcd);
                                }
                                case com.lifepilot.llm.stream.UsageEvent u -> {
                                    // UsageEvent 契约是累计值（见 UsageEvent Javadoc），多次发出代表累计更新（非增量叠加）。
                                    // 用 Math.max 兜底防止后发的 chunk 累计值意外回退（理论不会发生但防御性处理）。
                                    accumulatedPromptTokens[0] = Math.max(accumulatedPromptTokens[0], u.inputTokens());
                                    accumulatedCompletionTokens[0] = Math.max(accumulatedCompletionTokens[0], u.outputTokens());
                                    if (u.cachedInputTokens() > 0) {
                                        accumulatedCachedTokens[0] = Math.max(accumulatedCachedTokens[0], u.cachedInputTokens());
                                    }
                                }
                                case com.lifepilot.llm.stream.DoneEvent ignored -> { /* 流结束信号，无需特殊处理 */ }
                                case com.lifepilot.llm.stream.ErrorEvent err ->
                                        this.streamingError = new RuntimeException(err.code() + ": " + err.message());
                            }
                        } catch (Exception e) {
                            log.warn("流式事件处理异常，跳过: error={}", e.getMessage());
                        }
                    })
                    .doOnError(e -> {
                        log.warn("流式调用异常: scene={}, provider={}, error={}",
                                scene2, chatModelInfo.serviceId(), describeProviderError(e));
                        this.streamingError = e instanceof Exception ex ? ex : new RuntimeException(e);
                    })
                    .blockLast();
        } catch (Exception e) {
            // doOnError 已捕获异常到 streamingError，blockLast 会重新抛出同一异常；
            // 统一由下方 streamingError 检查处理，避免双重抛出
            if (this.streamingError == null) {
                this.streamingError = e;
            }
            log.debug("文本流式 blockLast 异常（已捕获到 streamingError）: scene={}, error={}",
                    scene2, e.getMessage());
        }

        if (cancellationToken.isCancelled()) {
            log.debug("流式消费因取消信号停止: streamId={}", streamId);
        } else if (sseManager.getEmitter(streamId) == null) {
            log.debug("流式消费因 SSE 连接断开停止: streamId={}", streamId);
        }
        flushPendingTokenBatch();

        throwAndClearStreamingErrorIfPresent("流式消费");

        Instant callEnd = Instant.now();
        String collectedContent = contentBuilder.toString();
        this.finalContent = collectedContent;

        // 流式响应为空时构造空内容 ChatResponse
        List<AssistantMessage.ToolCall> toolCalls = toolCallAggregator.toolCalls();

        if (collectedContent.isEmpty() && toolCallAggregator.isEmpty()) {
            log.warn("流式响应为空: scene={}, provider={}, model={}",
                    scene2, chatModelInfo.serviceId(), chatModelInfo.modelName());
            var emptyMessage = new AssistantMessage("");
            var generation = new Generation(emptyMessage);
            // LlmStreamEvent 路径下不再持有原始 ChatResponse chunk，无 metadata 兜底
            ChatResponse emptyResponse = new ChatResponse(List.of(generation));
            helper.recordStreamingLlmStep(traceContext, callStart, providerId, modelId,
                    scene2, emptyResponse, null);
            return emptyResponse;
        }

        // tool call 事件已由 pushReactStepEvent 自动推送

        ChatResponse chatResponse = buildChatResponseFromStream(
                collectedContent, toolCalls,
                accumulatedPromptTokens[0], accumulatedCompletionTokens[0]);

        long ttftMs = firstTokenTime[0] != null
                ? Duration.between(callStart, firstTokenTime[0]).toMillis() : -1;
        long totalMs = Duration.between(callStart, callEnd).toMillis();
        var timings = streamingTimings();
        long cachedTokens = accumulatedCachedTokens[0];
        long promptTokens = accumulatedPromptTokens[0];
        // cache 命中展示为 "cache=8063(92%)" 片段, 无命中省略 (避免噪音)
        String cacheInfo = cachedTokens > 0 && promptTokens > 0
                ? " cache=" + cachedTokens + "(" + (cachedTokens * 100 / promptTokens) + "%)"
                : "";
        log.info("LLM 完成 scene={} model={} prompt={}{} out={} ttft={}ms total={}ms tools={}",
                scene2, chatModelInfo.modelName(),
                promptTokens, cacheInfo,
                accumulatedCompletionTokens[0],
                ttftMs, totalMs,
                toolCalls.size());
        // DEBUG 保留分段耗时 + provider id, 仅排查性能/路由问题时查看
        if (log.isDebugEnabled()) {
            log.debug("LLM 完成 DEBUG provider={} firstReasoning={}ms firstTokenSse={}ms streamToFirstToken={}ms contentLen={}",
                    chatModelInfo.serviceId(),
                    timingOrDefault(timings, "requestReceivedToFirstReasoningEventMs"),
                    timingOrDefault(timings, "requestReceivedToFirstTokenSseMs"),
                    timingOrDefault(timings, "modelStreamStartToFirstTokenMs"),
                    collectedContent.length());
        }

        helper.recordStreamingLlmStep(traceContext, callStart, providerId, modelId,
                scene2, chatResponse, null);

        return chatResponse;
    }

    /**
     * 从流式收集的数据构造 ChatResponse。
     *
     * <p>仅基于 LlmStreamEvent 流累加的 Token 用量构建 metadata；
     * Phase 4 升级为消费 LlmStreamEvent 后不再持有原始 ChatResponse chunk，
     * 故无需 lastChunk 兜底。
     *
     * @param collectedContent          流式收集的完整文本内容
     * @param toolCalls                 流式收集的 tool call 列表（可能为空）
     * @param accumulatedPromptTokens   累加的 prompt token 数
     * @param accumulatedCompletionTokens 累加的 completion token 数
     * @return 构造好的 ChatResponse
     */
    private ChatResponse buildChatResponseFromStream(
            String collectedContent,
            List<AssistantMessage.ToolCall> toolCalls,
            long accumulatedPromptTokens,
            long accumulatedCompletionTokens) {
        AssistantMessage assistantMessage;
        if (!toolCalls.isEmpty()) {
            var builder = AssistantMessage.builder().toolCalls(toolCalls);
            if (collectedContent != null && !collectedContent.isBlank()) {
                builder.content(collectedContent);
            }
            assistantMessage = builder.build();
        } else {
            assistantMessage = new AssistantMessage(collectedContent);
        }
        var generation = new Generation(assistantMessage);

        if (accumulatedPromptTokens > 0 || accumulatedCompletionTokens > 0) {
            var usage = new DefaultUsage(
                    (int) accumulatedPromptTokens, (int) accumulatedCompletionTokens);
            return new ChatResponse(List.of(generation),
                    ChatResponseMetadata.builder().usage(usage).build());
        }
        return new ChatResponse(List.of(generation));
    }

    /**
     * 非流式 LLM 调用降级方法。
     *
     * <p>当 ChatModel 不支持流式调用时，作为降级路径使用。</p>
     */
    private ChatResponse callLlmNonStreaming(GenerationRouter.ChatModelInfo chatModelInfo,
                                             Prompt prompt,
                                             @Nullable TraceContext traceContext) {
        String scene = config.getLoop().getLlmScene();

        ChatResponse chatResponse = chatModelInfo.chatModel().call(prompt);
        var result = chatResponse.getResult();
        if (result == null || result.getOutput() == null) {
            log.warn("非流式降级调用返回空响应: provider={}, model={}",
                    chatModelInfo.serviceId(), chatModelInfo.modelName());
            helper.recordStreamingLlmStep(traceContext, Instant.now(), providerId, modelId,
                    scene, chatResponse, null);
            return chatResponse;
        }
        var assistantMsg = result.getOutput();
        // 同步降级路径补齐 reasoning_content：Spring AI 把 OpenAI 协议的 reasoning_content
        // 也以 metadata key="reasoningContent" 塞入 AssistantMessage；保持与流式路径行为对称。
        var nonStreamingMetadata = assistantMsg.getMetadata();
        if (nonStreamingMetadata != null) {
            Object rc = nonStreamingMetadata.get("reasoningContent");
            if (rc instanceof String reasoning) {
                // 不跳过空串 —— thinking 模式短响应可能返空 reasoning，多轮契约仍需回传
                reasoningContentBuilder.append(reasoning);
            }
        }

        if (assistantMsg.hasToolCalls()) {
            // tool call 事件已由 pushReactStepEvent 自动推送
            helper.recordStreamingLlmStep(traceContext, Instant.now(), providerId, modelId,
                    scene, chatResponse, null);
            return chatResponse;
        }

        String content = assistantMsg.getText();
        this.finalContent = content;

        if (content != null && !content.isBlank()) {
            streamContentToSse(content);
        }

        helper.recordStreamingLlmStep(traceContext, Instant.now(), providerId, modelId,
                scene, chatResponse, null);

        return chatResponse;
    }

    /** 将文本内容逐段推送为 SSE TOKEN 事件。 */
    private void streamContentToSse(String content) {
        enqueueTokenChunk(content);
        flushPendingTokenBatch();
    }

    /**
     * 将单个流式 token 推送为 SSE 事件。
     *
     * @param token LLM 流式输出的单个 token
     */
    private void pushTokenToSse(String token) {
        enqueueTokenChunk(token);
    }

    /**
     * 基于 LlmStreamEvent 流的 ToolCallDelta 发出工具调用准备事件。
     *
     * <p>仅在首次出现 ToolCallDelta 时触发一次，后续 delta 不再发出。
     */
    private void emitToolCallPreviewForDelta(com.lifepilot.llm.stream.ToolCallDelta delta,
                                              boolean[] toolCallPreviewSent) {
        if (toolCallPreviewSent[0]) {
            return;
        }
        emitToolCallPreviewByName(delta.name(), toolCallPreviewSent);
    }

    private void emitToolCallPreviewByName(@Nullable String toolName, boolean[] toolCallPreviewSent) {
        toolCallPreviewSent[0] = true;
        flushPendingTokenBatch();
        String description = toolName != null && !toolName.isBlank()
                ? "模型已进入工具调用阶段，正在整理参数：" + toolName
                : "模型已进入工具调用阶段，正在整理参数。";
        helper.sendReasoningEvent(
                sseManager,
                streamId,
                sessionId,
                turnId,
                "PROGRESS",
                "准备调用工具",
                description,
                toolName,
                Map.of("phase", "stream_tool_call_preview"),
                eventBuffer
        );
    }

    /**
     * 推送推理过程事件到 SSE 通道。
     *
     * <p>由 LlmStreamEvent 流的 ReasoningChunk 触发；空字符串或 null 跳过。
     *
     * @param reasoning 推理文本增量
     */
    private void pushReasoningToSse(String reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return;
        }
        var data = Map.<String, Object>of(
                "sessionId", sessionId,
                "turnId", turnId,
                "delta", reasoning
        );
        if (eventBuffer != null) {
            eventBuffer.offer(SseEventType.REASONING, data);
        } else {
            sseManager.sendEvent(streamId, SseEventType.REASONING, data);
        }
    }

    private void markVisibleOutputEmitted() {
        if (loopContext != null) {
            loopContext.markVisibleOutputEmitted();
        }
    }

    private void markModelStreamStarted(Instant startedAt) {
        if (loopContext != null) {
            loopContext.markModelStreamStarted(startedAt);
        }
    }

    private void markFirstModelToken(Instant tokenAt) {
        if (loopContext != null) {
            loopContext.markFirstModelToken(tokenAt);
        }
    }

    private Map<String, Long> streamingTimings() {
        return loopContext != null ? loopContext.streamingTimingsMs() : Map.of();
    }

    private long timingOrDefault(Map<String, Long> timings, String key) {
        return timings.getOrDefault(key, -1L);
    }

    private void enqueueTokenChunk(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (pendingTokenBatch.isEmpty()) {
            tokenBatchOpenedAt = Instant.now();
        }
        pendingTokenBatch.append(text);
        Instant openedAt = tokenBatchOpenedAt;
        if (pendingTokenBatch.length() >= TOKEN_BATCH_MAX_CHARS
                || (openedAt != null && Duration.between(openedAt, Instant.now()).compareTo(TOKEN_BATCH_MAX_DELAY) >= 0)) {
            flushPendingTokenBatch();
        }
    }

    private void flushPendingTokenBatch() {
        if (pendingTokenBatch.isEmpty()) {
            return;
        }
        Instant emittedAt = Instant.now();
        String content = pendingTokenBatch.toString();
        pendingTokenBatch.setLength(0);
        tokenBatchOpenedAt = null;
        markVisibleOutputEmitted();
        if (loopContext != null) {
            loopContext.markFirstTokenSse(emittedAt);
        }
        var tokenData = Map.<String, Object>of(
                "sessionId", sessionId,
                "turnId", turnId,
                "content", content,
                "index", nextTokenIndex++
        );
        if (eventBuffer != null) {
            eventBuffer.offer(SseEventType.TOKEN, tokenData);
        } else {
            sseManager.sendEvent(streamId, SseEventType.TOKEN, tokenData);
        }
    }

    /** 清空 token 合批缓冲，防止跨迭代残留。 */
    private void clearPendingTokenBatch() {
        pendingTokenBatch.setLength(0);
        tokenBatchOpenedAt = null;
    }

    private String describeProviderError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientResponseException webClientResponseException) {
                return formatWebClientError(webClientResponseException);
            }
            current = current.getCause();
        }
        return error.getMessage();
    }

    private String formatWebClientError(WebClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return exception.getMessage();
        }
        String normalized = body.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() > 1000) {
            normalized = normalized.substring(0, 1000) + "...";
        }
        return exception.getMessage() + ", responseBody=" + normalized;
    }

    public boolean hasStreamingError() { return streamingError != null; }
    @Nullable public Exception getStreamingError() { return streamingError; }
    @Nullable public String getFinalContent() { return finalContent; }

    /**
     * 获取本次调用累加的完整推理过程文本（DeepSeek V4 / Qwen3 等推理模型）。
     *
     * @return 完整 reasoning_content；非推理模型或本次调用无 reasoning chunk 返回空串
     */
    @Override
    public String getFinalReasoningContent() {
        return reasoningContentBuilder.toString();
    }

    @Override public boolean recordsLlmStep() { return true; }
    @Override public String getProviderId() { return providerId; }
    @Override public String getModelId() { return modelId; }
}
