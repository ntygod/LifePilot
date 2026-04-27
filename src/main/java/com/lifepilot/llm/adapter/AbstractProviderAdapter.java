package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.profile.BaseAdapterType;
import com.lifepilot.llm.stream.ContentChunk;
import com.lifepilot.llm.stream.LlmStreamEvent;
import com.lifepilot.llm.stream.ToolCallDelta;
import com.lifepilot.llm.stream.UsageEvent;
import com.lifepilot.generation.support.JsonOutputParser;
import org.springframework.ai.converter.BeanOutputConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.lang.Nullable;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider 适配器抽象基类 — 基于 Spring AI 的统一实现骨架。
 *
 * <p>封装 {@link ChatModel}、{@link EmbeddingModel}（可选）和 {@link ChatClient}（延迟构建），
 * 提供统一的调用接口，支持超时控制。子类按 provider 特性扩展（OpenAI 兼容 / Anthropic / Ollama 等）。
 *
 * <p>本类承载 Phase 3 之前 {@code SpringAiProviderAdapter} 单类的全部行为，含 streamEvents 默认实现（基于
 * {@link #chunkToEvents}）；子类（OpenAiBase / Anthropic / Ollama）目前完全复用基类逻辑，
 * Phase 10 起按 ThinkingProtocol 解析原始 SSE chunk 时再按需重写 streamEvents 注入 ReasoningChunk。
 *
 * @author zsg
 * @since 2026-04-27
 */
public abstract non-sealed class AbstractProviderAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractProviderAdapter.class);
    private static final java.net.http.HttpClient SHARED_HTTP_CLIENT = java.net.http.HttpClient.newHttpClient();

    protected final ProviderConfig config;
    protected final BaseAdapterType baseAdapter;
    protected final ChatModel chatModel;
    @Nullable
    protected final EmbeddingModel embeddingModel;
    protected final List<CallAdvisor> defaultAdvisors;

    @Nullable
    private volatile ChatClient chatClient;

    protected AbstractProviderAdapter(ProviderConfig config,
                                      BaseAdapterType baseAdapter,
                                      ChatModel chatModel,
                                      @Nullable EmbeddingModel embeddingModel,
                                      @Nullable List<CallAdvisor> defaultAdvisors) {
        this.config = config;
        this.baseAdapter = baseAdapter;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.defaultAdvisors = defaultAdvisors != null ? List.copyOf(defaultAdvisors) : List.of();
    }

    @Override
    public LlmResponse call(String prompt, @Nullable String outputSchema, Duration timeout) {
        long start = System.currentTimeMillis();
        ChatResponse response = executeWithTimeout(
                () -> callViaClient(buildPrompt(prompt, outputSchema, false)),
                timeout
        );
        long latencyMs = System.currentTimeMillis() - start;
        return toLlmResponse(response, latencyMs);
    }

    @Override
    public <T> T callEntity(String prompt, Class<T> responseType) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(responseType);
        String rawText = executeStructuredContentCall(prompt, converter);
        try {
            return converter.convert(rawText);
        } catch (Exception e) {
            if (isJsonParseError(e)) {
                log.debug("结构化输出解析失败，尝试 JSON 修复: error={}", e.getMessage());
                return JsonOutputParser.parse(rawText, responseType);
            }
            throw e;
        }
    }

    /**
     * 判断异常是否为 JSON 解析错误。
     */
    private boolean isJsonParseError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getClass().getName();
            if (msg.contains("JsonParseException") || msg.contains("JsonMappingException")
                    || msg.contains("JsonProcessingException")) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("Could not parse the given text")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 修复 LLM 返回的常见 JSON 格式问题。
     *
     * <p>处理：字符串值内未转义的双引号、Markdown 代码块包裹、尾部逗号等。
     */
    public static String repairJson(String raw) {
        return JsonOutputParser.repairJson(raw);
    }

    @Override
    public float[] embed(String text) {
        if (embeddingModel == null || !config.hasCapability(ProviderCapability.EMBEDDING)) {
            throw new UnsupportedOperationException(
                    "Provider 不支持 EMBEDDING 能力: id=" + config.id());
        }
        return embeddingModel.embed(text);
    }

    @Override
    public Flux<String> stream(String prompt) {
        if (!config.supportsStreaming()) {
            throw new UnsupportedOperationException(
                    "Provider 不支持 STREAMING 能力: id=" + config.id());
        }
        return streamViaClient(buildPrompt(prompt, null, true));
    }

    /**
     * 把流式 ChatResponse 转成 {@link LlmStreamEvent} 流。
     *
     * <p>默认实现：通过 {@code chatModel.stream(prompt)} 拉响应，
     * 用 {@link #chunkToEvents(ChatResponse)} 把每个 chunk 转成 ContentChunk + ToolCallDelta + UsageEvent。
     * 子类需要差异化（如 OpenAI 协议特殊解析、Anthropic content block 流）时重写。
     *
     * <p>简化版：暂不发 ReasoningChunk / DoneEvent（Phase 10 通过 SSE 旁路解析时补完）。
     *
     * @param prompt        Spring AI Prompt
     * @param toolCallbacks tool callbacks（默认实现未使用，重写时按需消费）
     * @return LlmStreamEvent 流
     */
    public Flux<LlmStreamEvent> streamEvents(Prompt prompt, List<ToolCallback> toolCallbacks) {
        return chatModel.stream(prompt).flatMap(this::chunkToEvents);
    }

    /**
     * 把 Spring AI 流式 ChatResponse chunk 转换成 LlmStreamEvent 序列（基础实现）。
     *
     * <p>简化版：
     * <ul>
     *   <li>chunk 含文本 → 发 {@link ContentChunk}</li>
     *   <li>chunk 含 tool_calls → 按 index 发 {@link ToolCallDelta}</li>
     *   <li>chunk 含 usage → 发 {@link UsageEvent}（不带 reasoningTokens / cachedInputTokens 维度）</li>
     * </ul>
     *
     * <p>ReasoningChunk / DoneEvent / ErrorEvent / 富 UsageEvent（cached / reasoning tokens）
     * 由 Phase 10 子类按 ThinkingProtocol 解析原始 SSE chunk 时补完。
     *
     * <p>本方法被 streamEvents 默认实现调用；OpenAiBase / Anthropic / Ollama 三个子类均通过继承共用。
     *
     * @param chunk 流式 ChatResponse chunk
     * @return 对应的 LlmStreamEvent 序列（可能为空）
     */
    protected Flux<LlmStreamEvent> chunkToEvents(ChatResponse chunk) {
        var events = new java.util.ArrayList<LlmStreamEvent>(4);
        var result = chunk.getResult();
        if (result != null && result.getOutput() != null) {
            var output = result.getOutput();
            String text = output.getText();
            if (text != null && !text.isEmpty()) {
                events.add(new ContentChunk(text));
            }
            if (output.hasToolCalls()) {
                var toolCalls = output.getToolCalls();
                for (int i = 0; i < toolCalls.size(); i++) {
                    var tc = toolCalls.get(i);
                    events.add(new ToolCallDelta(i, tc.id(), tc.name(),
                            tc.arguments() != null ? tc.arguments() : ""));
                }
            }
        }
        var meta = chunk.getMetadata();
        var usage = meta != null ? meta.getUsage() : null;
        if (usage != null) {
            int inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            int outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            if (inputTokens > 0 || outputTokens > 0) {
                events.add(new UsageEvent(inputTokens, outputTokens, null, 0));
            }
        }
        return Flux.fromIterable(events);
    }

    @Override
    public Optional<ChatClient> chatClient() {
        return Optional.of(ensureChatClient());
    }

    /**
     * 获取或创建缓存的 ChatClient（已挂载 Advisor 链：Guardrail → Trace → ...）。
     */
    protected ChatClient ensureChatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    var builder = ChatClient.builder(chatModel);
                    if (!defaultAdvisors.isEmpty()) {
                        builder.defaultAdvisors(defaultAdvisors.toArray(new CallAdvisor[0]));
                    }
                    chatClient = builder.build();
                }
            }
        }
        return chatClient;
    }

    /**
     * 通过 ChatClient 发起同步调用，确保 Advisor 链生效。
     */
    protected ChatResponse callViaClient(Prompt prompt) {
        return ensureChatClient()
                .prompt(prompt)
                .call()
                .chatResponse();
    }

    /**
     * 通过 ChatClient 发起流式调用，统一调用路径。
     */
    protected Flux<String> streamViaClient(Prompt prompt) {
        return ensureChatClient()
                .prompt(prompt)
                .stream()
                .content()
                .filter(text -> text != null && !text.isEmpty());
    }

    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(15);

    @Override
    public boolean healthCheck() {
        try {
            // TEI 服务（embedding / reranker）没有 OpenAI 兼容的 chat 接口，通过 /health 端点检查
            if (baseAdapter == BaseAdapterType.TEI) {
                return checkHealthEndpoint(config.apiUrl());
            }
            // EMBEDDING 类型通过 embeddingModel 验证
            if (config.hasCapability(ProviderCapability.EMBEDDING) && embeddingModel != null) {
                float[] embedding = embeddingModel.embed("ping");
                return embedding.length > 0;
            }
            // CHAT 及其他类型通过 chatModel 验证连通性（带超时保护，避免无限等待）
            ChatResponse response = executeWithTimeout(
                    () -> chatModel.call(new Prompt("ping")), HEALTH_CHECK_TIMEOUT);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return false;
            }
            String text = response.getResult().getOutput().getText();
            return text != null && !text.isBlank();
        } catch (Exception e) {
            log.debug("Provider 健康检查失败: id={}, error={}", config.id(), e.getMessage());
            return false;
        }
    }

    /**
     * 通过 HTTP GET /health 端点检查 Provider 健康状态（适用于 TEI 等非 LLM 服务）。
     *
     * @param apiUrl Provider 的 API 基础地址
     * @return 健康返回 true
     */
    private boolean checkHealthEndpoint(String apiUrl) {
        try {
            String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
            var uri = java.net.URI.create(baseUrl + "/health");
            var request = java.net.http.HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = SHARED_HTTP_CLIENT
                    .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Provider /health 端点检查失败: id={}, error={}", config.id(), e.getMessage());
            return false;
        }
    }

    @Override
    public LlmResponse callWithMedia(String prompt,
                                     List<MediaContent> mediaContents,
                                     @Nullable String outputSchema,
                                     Duration timeout) {
        if (!config.hasCapability(ProviderCapability.VISION)) {
            throw new UnsupportedOperationException("Provider 不支持 VISION 能力: " + config.id());
        }

        List<Media> mediaList = mediaContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        long start = System.currentTimeMillis();
        ChatResponse response = executeWithTimeout(
                () -> callViaClient(buildPrompt(userMessage, outputSchema, false)),
                timeout
        );
        long latencyMs = System.currentTimeMillis() - start;
        return toLlmResponse(response, latencyMs);
    }

    @Override
    public Flux<String> streamWithMedia(String prompt, List<MediaContent> mediaContents) {
        if (!config.hasCapability(ProviderCapability.VISION)) {
            throw new UnsupportedOperationException("Provider 不支持 VISION 能力: " + config.id());
        }

        List<Media> mediaList = mediaContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        return streamViaClient(buildPrompt(userMessage, null, true));
    }

    @Override
    public LlmResponse callWithVideo(String text,
                                     String videoUri,
                                     @Nullable String outputSchema,
                                     Duration timeout) {
        if (!config.hasCapability(ProviderCapability.NATIVE_VIDEO)) {
            throw new UnsupportedOperationException("Provider 不支持 NATIVE_VIDEO 能力: " + config.id());
        }

        long start = System.currentTimeMillis();
        ChatResponse response = executeWithTimeout(() -> {
            // 构造包含视频 URI 引用的 UserMessage
            var media = Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType("video/*"))
                    .data(java.net.URI.create(videoUri).toURL())
                    .build();
            var userMessage = UserMessage.builder().text(text).media(media).build();
            return callViaClient(buildPrompt(userMessage, outputSchema, false));
        }, timeout);
        long latencyMs = System.currentTimeMillis() - start;
        return toLlmResponse(response, latencyMs);
    }

    public ProviderConfig config() {
        return config;
    }

    @Override
    public LlmResponse callWithAudio(String prompt,
                                     List<MediaContent> audioContents,
                                     @Nullable String outputSchema,
                                     Duration timeout) {
        if (!config.hasCapability(ProviderCapability.NATIVE_AUDIO)) {
            throw new UnsupportedOperationException("Provider 不支持 NATIVE_AUDIO 能力: " + config.id());
        }

        List<Media> mediaList = audioContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        long start = System.currentTimeMillis();
        ChatResponse response = executeWithTimeout(
                () -> callViaClient(buildPrompt(userMessage, outputSchema, false)),
                timeout
        );
        long latencyMs = System.currentTimeMillis() - start;
        return toLlmResponse(response, latencyMs);
    }

    @Override
    public Flux<String> streamWithAudio(String prompt, List<MediaContent> audioContents) {
        if (!config.hasCapability(ProviderCapability.NATIVE_AUDIO)) {
            throw new UnsupportedOperationException("Provider 不支持 NATIVE_AUDIO 能力: " + config.id());
        }

        List<Media> mediaList = audioContents.stream()
                .map(mc -> new Media(MimeTypeUtils.parseMimeType(mc.mimeType()), new ByteArrayResource(mc.data())))
                .toList();

        var builder = UserMessage.builder().text(prompt);
        for (Media media : mediaList) {
            builder.media(media);
        }
        UserMessage userMessage = builder.build();

        return streamViaClient(buildPrompt(userMessage, null, true));
    }

    public ChatModel chatModel() {
        return chatModel;
    }

    @Nullable
    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    protected Prompt buildPrompt(String prompt, @Nullable String outputSchema, boolean streamUsage) {
        return new Prompt(
                maybeAppendStructuredOutputInstruction(prompt, outputSchema),
                ProviderChatOptionsFactory.create(
                        providerDescriptor(),
                        chatModel,
                        config.modelName(),
                        null,
                        null,
                        false,
                        streamUsage,
                        outputSchema
                )
        );
    }

    protected Prompt buildPrompt(Message message, @Nullable String outputSchema, boolean streamUsage) {
        return new Prompt(
                maybeAppendStructuredOutputInstruction(message, outputSchema),
                ProviderChatOptionsFactory.create(
                        providerDescriptor(),
                        chatModel,
                        config.modelName(),
                        null,
                        null,
                        false,
                        streamUsage,
                        outputSchema
                )
        );
    }

    private <T> String executeStructuredContentCall(String prompt, BeanOutputConverter<T> converter) {
        String effectivePrompt = prompt;
        if (!supportsProtocolStructuredOutput()) {
            effectivePrompt = prompt + System.lineSeparator() + System.lineSeparator() + converter.getFormat();
        }
        return Optional.ofNullable(
                ensureChatClient()
                        .prompt(new Prompt(
                                effectivePrompt,
                                ProviderChatOptionsFactory.create(
                                        providerDescriptor(),
                                        chatModel,
                                        config.modelName(),
                                        null,
                                        null,
                                        false,
                                        false,
                                        converter.getJsonSchema()
                                )
                        ))
                        .call()
                        .content()
        ).orElse("");
    }

    private boolean supportsProtocolStructuredOutput() {
        return ProviderChatOptionsFactory.supportsProtocolStructuredOutput(providerDescriptor());
    }

    private ProviderChatOptionsFactory.ProviderDescriptor providerDescriptor() {
        return new ProviderChatOptionsFactory.ProviderDescriptor(baseAdapter, config.apiUrl());
    }

    private String maybeAppendStructuredOutputInstruction(String prompt, @Nullable String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank() || supportsProtocolStructuredOutput()) {
            return prompt;
        }
        return prompt + System.lineSeparator() + System.lineSeparator()
                + """
                请仅输出一个合法 JSON 对象，不要输出任何额外说明、Markdown 代码块或前后缀。
                输出必须符合以下 JSON Schema：
                <json_schema>
                %s
                </json_schema>
                """.formatted(outputSchema.trim());
    }

    private Message maybeAppendStructuredOutputInstruction(Message message, @Nullable String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank() || supportsProtocolStructuredOutput()) {
            return message;
        }
        if (message instanceof UserMessage userMessage) {
            var builder = UserMessage.builder()
                    .text(maybeAppendStructuredOutputInstruction(userMessage.getText(), outputSchema));
            for (Media media : userMessage.getMedia()) {
                builder.media(media);
            }
            return builder.build();
        }
        return message;
    }

    private LlmResponse toLlmResponse(ChatResponse response, long latencyMs) {
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        int inputTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int outputTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        String content = Optional.ofNullable(response.getResult())
                .map(result -> result.getOutput())
                .map(output -> output.getText())
                .orElse("");
        return LlmResponse.simple(
                content,
                inputTokens,
                outputTokens,
                config.id(),
                config.modelName(),
                latencyMs
        );
    }

    protected <T> T executeWithTimeout(Callable<T> action, Duration timeout) {
        // 使用虚拟线程执行器进行超时控制。注意：try-with-resources 的 close() 会等待任务完成，
        // 但由于已调用 future.cancel(true) 且虚拟线程响应中断，不会无限阻塞。
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var future = executor.submit(action);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                throw new RuntimeException("LLM 调用超时: " + timeout.toSeconds() + "s", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM 调用被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(cause);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败", e);
        } finally {
            executor.close();
        }
    }
}
