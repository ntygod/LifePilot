package com.lifepilot.agent.callback;

import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.a2ui.A2uiComponentCatalog;
import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser;
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

    private final AgentConfigProperties config;
    private final GenerationRouter generationRouter;
    @Nullable private final MultimodalRouter multimodalRouter;
    private final CallbackHelper helper;
    private final CancellationToken cancellationToken;
    @Nullable private final AgentLoopContext loopContext;

    private final SseSessionManager sseManager;
    private final String streamId;
    private final String sessionId;
    private final String turnId;
    private final AgentRequest request;

    private String providerId = IterationCallback.DEFAULT_MODEL_ID;
    private String modelId = IterationCallback.DEFAULT_MODEL_ID;
    @Nullable private Exception streamingError;
    @Nullable private String finalContent;

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

        // 动态路由：检查 messages 中 UserMessage 是否包含 Media 对象
        boolean messagesHaveMedia = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .anyMatch(um -> !um.getMedia().isEmpty());

        // 多模态流式路由
        if (messagesHaveMedia && multimodalRouter != null) {
            return callMultimodalStreaming(req, messages, scene, traceContext);
        }

        if (messagesHaveMedia) {
            log.warn("消息包含媒体内容但 MultimodalRouter 不可用，回退到纯文本路由");
        }

        return callTextStreaming(req, messages, toolCallbacks, scene, traceContext);
    }

    /** 多模态流式路由。 */
    private ChatResponse callMultimodalStreaming(AgentRequest req, List<Message> messages,
                                                 String scene, @Nullable TraceContext traceContext) {
        var mediaContents = req.mediaContents() != null && !req.mediaContents().isEmpty()
                ? req.mediaContents()
                : helper.extractMediaContentsFromMessages(messages);
        var multimodalRequest = new MultimodalRequest(
                scene, helper.buildConversationContextText(messages),
                mediaContents, null, req.preferredProvider(), null);
        StreamingLlmResponse streamingResponse = multimodalRouter.streamWithInfo(multimodalRequest);
        this.providerId = streamingResponse.providerId();
        this.modelId = streamingResponse.modelId();
        log.debug("流式多模态路由开始: scene={}, provider={}, model={}",
                scene, this.providerId, this.modelId);

        StreamingA2uiParser a2uiParser = helper.isA2uiEnabled() ? new StreamingA2uiParser() : null;
        Instant callStart = Instant.now();
        var contentBuilder = new StringBuilder();
        final Instant[] firstTokenTime = {null};

        streamingResponse.stream()
                .takeWhile(token -> !cancellationToken.isCancelled()
                        && sseManager.getEmitter(streamId) != null)
                .doOnNext(token -> {
                    contentBuilder.append(token);
                    if (firstTokenTime[0] == null) firstTokenTime[0] = Instant.now();
                    pushTokenToSse(token, a2uiParser);
                })
                .doOnError(e -> {
                    log.warn("流式多模态调用异常: scene={}, error={}", scene, describeProviderError(e));
                    this.streamingError = e instanceof Exception ex ? ex : new RuntimeException(e);
                })
                // blockLast() 在虚拟线程上下文中调用是安全的，不会阻塞平台线程池
                .blockLast();

        if (cancellationToken.isCancelled()) {
            log.debug("多模态流式消费因取消信号停止: streamId={}", streamId);
        } else if (sseManager.getEmitter(streamId) == null) {
            log.debug("多模态流式消费因 SSE 连接断开停止: streamId={}", streamId);
        }
        if (this.streamingError != null) {
            log.warn("多模态流式消费完成但存在未传播的异常，重新抛出: error={}", this.streamingError.getMessage());
            throw this.streamingError instanceof RuntimeException re ? re : new RuntimeException(this.streamingError);
        }

        flushA2uiParser(a2uiParser);
        String collectedContent = contentBuilder.toString();
        this.finalContent = collectedContent;

        Instant callEnd = Instant.now();
        long ttftMs = firstTokenTime[0] != null ? Duration.between(callStart, firstTokenTime[0]).toMillis() : -1;
        long totalMs = Duration.between(callStart, callEnd).toMillis();
        log.info("多模态流式调用完成: scene={}, provider={}, model={}, ttft={}ms, total={}ms",
                scene, this.providerId, this.modelId, ttftMs, totalMs);

        ChatResponse chatResponse = helper.adaptToChatResponse(
                new LlmResponse(collectedContent, 0, 0, this.providerId, this.modelId, 0, false));
        helper.recordStreamingLlmStep(traceContext, callStart, providerId, modelId, scene, chatResponse, null);
        return chatResponse;
    }

    /** 纯文本流式路由。 */
    private ChatResponse callTextStreaming(AgentRequest req, List<Message> messages,
                                           List<ToolCallback> toolCallbacks, String scene,
                                           @Nullable TraceContext traceContext) {
        String preferredProviderId = request.preferredProvider();

        // 提取 system 文本，通过 CallbackHelper 集中增强（流式约束 + A2UI）
        String systemText = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).getText())
                .findFirst().orElse("");

        String a2uiPrompt = helper.isA2uiEnabled()
                ? A2uiComponentCatalog.renderPrompt(helper.getA2uiMaxComponents())
                : null;
        String streamingSystemPrompt = helper.enhanceSystemPromptForStreaming(systemText, a2uiPrompt);

        // 先尝试用 ChatModel 做一次非流式调用检测 tool call
        var chatModelInfo = generationRouter.getChatModelWithInfo(scene, preferredProviderId, null);
        this.providerId = chatModelInfo.serviceId();
        this.modelId = chatModelInfo.modelName();

        var chatOptions = ProviderChatOptionsFactory.create(
                new ProviderChatOptionsFactory.ProviderDescriptor(
                        chatModelInfo.providerType(),
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
        // 注意：此处复用外层参数 scene，避免在流式消费过程中重新读取配置导致不一致

        StreamingA2uiParser a2uiParser = helper.isA2uiEnabled() ? new StreamingA2uiParser() : null;

        var contentBuilder = new StringBuilder();
        var toolCallAggregator = new StreamingToolCallAggregator();
        final ChatResponse[] lastChunk = {null};
        final Instant[] firstTokenTime = {null};
        // 累加流式 chunk 中的 Token 用量（部分 Provider 仅在最后一个 chunk 返回完整 usage）
        final long[] accumulatedPromptTokens = {0};
        final long[] accumulatedCompletionTokens = {0};

        Flux<ChatResponse> flux = chatModelInfo.chatModel().stream(prompt);

        // blockLast() 在虚拟线程上下文中调用是安全的，不会阻塞平台线程池
        try {
            flux.takeWhile(chunk -> !cancellationToken.isCancelled()
                            && sseManager.getEmitter(streamId) != null)
            .doOnNext(chunk -> {
                try {
                    lastChunk[0] = chunk;
                    var output = chunk.getResult().getOutput();

                    // 累加每个 chunk 的 usage（取最大值，兼容增量和累计两种模式）
                    var chunkUsage = chunk.getMetadata().getUsage();
                    if (chunkUsage != null) {
                        accumulatedPromptTokens[0] = Math.max(accumulatedPromptTokens[0],
                                chunkUsage.getPromptTokens());
                        accumulatedCompletionTokens[0] = Math.max(accumulatedCompletionTokens[0],
                                chunkUsage.getCompletionTokens());
                    }

                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        contentBuilder.append(text);
                        if (firstTokenTime[0] == null) {
                            firstTokenTime[0] = Instant.now();
                        }
                        pushTokenToSse(text, a2uiParser);
                    }

                    if (output.hasToolCalls()) {
                        toolCallAggregator.merge(output.getToolCalls());
                    }
                } catch (Exception e) {
                    log.warn("流式 chunk 处理异常，跳过: error={}", e.getMessage());
                }
            }).doOnError(e -> {
                log.warn("流式调用异常: scene={}, provider={}, error={}",
                        scene, chatModelInfo.serviceId(), describeProviderError(e));
                this.streamingError = e instanceof Exception ex ? ex : new RuntimeException(e);
            }).blockLast();
        } catch (Exception e) {
            log.error("流式调用失败: scene={}, provider={}, error={}",
                    scene, chatModelInfo.serviceId(), describeProviderError(e));
            throw e;
        }

        if (cancellationToken.isCancelled()) {
            log.debug("流式消费因取消信号停止: streamId={}", streamId);
        } else if (sseManager.getEmitter(streamId) == null) {
            log.debug("流式消费因 SSE 连接断开停止: streamId={}", streamId);
        }

        if (this.streamingError != null) {
            log.warn("流式消费完成但存在未传播的异常，重新抛出: error={}", this.streamingError.getMessage());
            throw this.streamingError instanceof RuntimeException re
                    ? re : new RuntimeException(this.streamingError);
        }

        Instant callEnd = Instant.now();
        String collectedContent = contentBuilder.toString();
        this.finalContent = collectedContent;

        flushA2uiParser(a2uiParser);

        // 流式响应为空时构造空内容 ChatResponse
        List<AssistantMessage.ToolCall> toolCalls = toolCallAggregator.toolCalls();

        if (collectedContent.isEmpty() && toolCallAggregator.isEmpty()) {
            log.warn("流式响应为空: scene={}, provider={}, model={}",
                    scene, chatModelInfo.serviceId(), chatModelInfo.modelName());
            var emptyMessage = new AssistantMessage("");
            var generation = new Generation(emptyMessage);
            ChatResponse emptyResponse = lastChunk[0] != null
                    ? new ChatResponse(List.of(generation), lastChunk[0].getMetadata())
                    : new ChatResponse(List.of(generation));
            helper.recordStreamingLlmStep(traceContext, callStart, providerId, modelId,
                    scene, emptyResponse, null);
            return emptyResponse;
        }

        // tool call 事件已由 pushReactStepEvent 自动推送

        ChatResponse chatResponse = buildChatResponseFromStream(
                collectedContent, toolCalls, lastChunk[0],
                accumulatedPromptTokens[0], accumulatedCompletionTokens[0]);

        long ttftMs = firstTokenTime[0] != null
                ? Duration.between(callStart, firstTokenTime[0]).toMillis() : -1;
        long totalMs = Duration.between(callStart, callEnd).toMillis();
        log.info("流式调用完成: scene={}, provider={}, model={}, ttft={}ms, total={}ms, " +
                        "promptTokens={}, completionTokens={}, toolCallCount={}, contentLength={}",
                scene, chatModelInfo.serviceId(), chatModelInfo.modelName(), ttftMs, totalMs,
                accumulatedPromptTokens[0], accumulatedCompletionTokens[0],
                toolCalls.size(), collectedContent.length());

        helper.recordStreamingLlmStep(traceContext, callStart, providerId, modelId,
                scene, chatResponse, null);

        return chatResponse;
    }

    /**
     * 从流式收集的数据构造 ChatResponse。
     *
     * <p>优先使用流式遍历中累加的 Token 用量（更准确），
     * 当累加值为 0 时回退到 lastChunk 的 metadata。</p>
     *
     * @param collectedContent          流式收集的完整文本内容
     * @param toolCalls                 流式收集的 tool call 列表（可能为空）
     * @param lastChunk                 最后一个流式 chunk（携带 metadata/usage，可空）
     * @param accumulatedPromptTokens   累加的 prompt token 数
     * @param accumulatedCompletionTokens 累加的 completion token 数
     * @return 构造好的 ChatResponse
     */
    private ChatResponse buildChatResponseFromStream(
            String collectedContent,
            List<AssistantMessage.ToolCall> toolCalls,
            @Nullable ChatResponse lastChunk,
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

        // 合并 lastChunk metadata 与累加 usage，取较大值
        if (lastChunk != null) {
            var baseMeta = lastChunk.getMetadata();
            var baseUsage = baseMeta.getUsage();
            long finalPrompt = accumulatedPromptTokens;
            long finalCompletion = accumulatedCompletionTokens;
            if (baseUsage != null) {
                finalPrompt = Math.max(finalPrompt, baseUsage.getPromptTokens());
                finalCompletion = Math.max(finalCompletion, baseUsage.getCompletionTokens());
            }
            // 如果累加 usage 比 lastChunk 更完整，构建新的带 usage 的 metadata
            if (finalPrompt > 0 || finalCompletion > 0) {
                var usage = new DefaultUsage((int) finalPrompt, (int) finalCompletion);
                return new ChatResponse(List.of(generation),
                        ChatResponseMetadata.builder().usage(usage).build());
            }
            return new ChatResponse(List.of(generation), baseMeta);
        }
        // 无 lastChunk 但有累加 usage 时
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
        var assistantMsg = chatResponse.getResult().getOutput();

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
        boolean a2uiEnabled = helper.isA2uiEnabled();
        StreamingA2uiParser a2uiParser = a2uiEnabled ? new StreamingA2uiParser() : null;
        int maxComponents = a2uiEnabled ? helper.getA2uiMaxComponents() : 0;
        int tokenIndex = 0;

        if (a2uiParser != null) {
            var segments = a2uiParser.feed(content);
            for (var segment : segments) {
                switch (segment) {
                    case StreamingA2uiParser.Segment.TextSegment(var text) -> {
                        if (!text.isEmpty()) {
                            markVisibleOutputEmitted();
                            sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                    "sessionId", sessionId, "turnId", turnId,
                                    "content", text, "index", tokenIndex++));
                        }
                    }
                    case StreamingA2uiParser.Segment.A2uiSegment(var json) -> {
                        var tree = helper.parseAndValidateA2uiTree(json, maxComponents);
                        if (tree != null) {
                            if (loopContext != null) {
                                loopContext.setLastCollectedA2uiTree(tree);
                            }
                            markVisibleOutputEmitted();
                            sseManager.sendEvent(streamId, SseEventType.UI, Map.of(
                                    "sessionId", sessionId, "turnId", turnId,
                                    "components", tree.components()));
                        }
                    }
                }
            }
            var remaining = a2uiParser.flush();
            for (var seg : remaining) {
                if (seg instanceof StreamingA2uiParser.Segment.TextSegment(var text)
                        && !text.isEmpty()) {
                    markVisibleOutputEmitted();
                    sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                            "sessionId", sessionId, "turnId", turnId,
                            "content", text, "index", tokenIndex++));
                }
            }
        } else {
            markVisibleOutputEmitted();
            sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                    "sessionId", sessionId, "turnId", turnId,
                    "content", content, "index", 0));
        }
    }

    /**
     * 将单个流式 token 推送为 SSE 事件，支持 A2UI 增量解析。
     *
     * @param token      LLM 流式输出的单个 token
     * @param a2uiParser A2UI 增量解析器实例（A2UI 未启用时为 null）
     */
    private void pushTokenToSse(String token, @Nullable StreamingA2uiParser a2uiParser) {
        if (a2uiParser != null) {
            int maxComponents = helper.getA2uiMaxComponents();
            var segments = a2uiParser.feed(token);
            for (var segment : segments) {
                switch (segment) {
                    case StreamingA2uiParser.Segment.TextSegment(var text) -> {
                        if (!text.isEmpty()) {
                            markVisibleOutputEmitted();
                            sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                                    "sessionId", sessionId, "turnId", turnId,
                                    "content", text, "index", 0));
                        }
                    }
                    case StreamingA2uiParser.Segment.A2uiSegment(var json) -> {
                        var tree = helper.parseAndValidateA2uiTree(json, maxComponents);
                        if (tree != null) {
                            if (loopContext != null) {
                                loopContext.setLastCollectedA2uiTree(tree);
                            }
                            markVisibleOutputEmitted();
                            sseManager.sendEvent(streamId, SseEventType.UI, Map.of(
                                    "sessionId", sessionId, "turnId", turnId,
                                    "components", tree.components()));
                        }
                    }
                }
            }
        } else {
            markVisibleOutputEmitted();
            sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                    "sessionId", sessionId, "turnId", turnId,
                    "content", token, "index", 0));
        }
    }

    /**
     * 流结束时刷出 A2UI 解析器剩余缓冲内容。
     *
     * @param a2uiParser A2UI 增量解析器实例（A2UI 未启用时为 null）
     */
    private void flushA2uiParser(@Nullable StreamingA2uiParser a2uiParser) {
        if (a2uiParser == null) return;
        var remaining = a2uiParser.flush();
        for (var seg : remaining) {
            if (seg instanceof StreamingA2uiParser.Segment.TextSegment(var text)
                    && !text.isEmpty()) {
                markVisibleOutputEmitted();
                sseManager.sendEvent(streamId, SseEventType.TOKEN, Map.of(
                        "sessionId", sessionId, "turnId", turnId,
                        "content", text, "index", 0));
            }
        }
    }

    private void markVisibleOutputEmitted() {
        if (loopContext != null) {
            loopContext.markVisibleOutputEmitted();
        }
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

    @Override public boolean recordsLlmStep() { return true; }
    @Override public String getProviderId() { return providerId; }
    @Override public String getModelId() { return modelId; }
}
