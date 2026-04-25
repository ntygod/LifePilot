package com.lifepilot.memory.support;

import java.time.Duration;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.modelservice.model.GenerationCapability;
import org.springframework.lang.Nullable;

/**
 * 测试用 {@link GenerationRouter} 替身：从 {@link LlmFixture} 取预录响应，不打真 LLM。
 *
 * <p>实现策略：直接继承 {@link GenerationRouter} 并传 {@code null} 给所有构造参数 —— 因为本类
 * 覆盖了所有会访问那些字段的 public 方法，父类字段不会被读取。</p>
 *
 * <p>支持的方法：
 * <ul>
 *   <li>{@link #call(String, String, String, String, String, GenerationCapability, Duration) call}
 *       及其 skipCache 重载：按最后一行 prompt 匹配 fixture，返回 {@code finalText}（或 fallback 到
 *       {@code toolCallsJson}）作为 {@link LlmResponse#content()}</li>
 * </ul>
 * 其余方法（callEntity / getChatClientWithInfo / streamWithInfo / resolveMaxContextWindow）在
 * Phase 0 阶段未用到，显式抛 {@link UnsupportedOperationException}。</p>
 *
 * <p>Task 9 补齐：{@link #getChatModelWithInfo} 返回包装 {@link FixtureBackedChatModel} 的
 * {@link ChatModelInfo}，使 {@code ReactAgentLoop.run(...)}（默认走
 * {@link com.lifepilot.agent.callback.NonStreamingCallback}）能在场景 E2E 测试里完整跑通
 * {@code ChatModel.call(Prompt)} 路径。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class FixtureBackedGenerationRouter extends GenerationRouter {

    private static final String FIXTURE_PROVIDER = "fixture";
    private static final String FIXTURE_MODEL = "fixture-llm";

    private final LlmFixture fixture;
    private final FixtureBackedChatModel chatModel;

    /**
     * 构造 fixture-backed router。
     *
     * @param fixture 已 {@link LlmFixture#load(String) load} 过的 fixture 实例
     */
    public FixtureBackedGenerationRouter(LlmFixture fixture) {
        super(null, null, null, null);
        this.fixture = fixture;
        this.chatModel = new FixtureBackedChatModel(fixture);
    }

    @Override
    public LlmResponse call(String scene,
                            String prompt,
                            @Nullable String outputSchema,
                            @Nullable String serviceId,
                            @Nullable String modelName,
                            GenerationCapability requiredCapability,
                            @Nullable Duration timeoutOverride) {
        return call(scene, prompt, outputSchema, serviceId, modelName, requiredCapability, timeoutOverride, false);
    }

    @Override
    public LlmResponse call(String scene,
                            String prompt,
                            @Nullable String outputSchema,
                            @Nullable String serviceId,
                            @Nullable String modelName,
                            GenerationCapability requiredCapability,
                            @Nullable Duration timeoutOverride,
                            boolean skipCache) {
        String lastUserMessage = extractLastUserMessage(prompt);
        LlmFixture.FixtureResponse response = fixture.matchAndRender(lastUserMessage);
        String content = response.finalText().isBlank() ? response.toolCallsJson() : response.finalText();
        return new LlmResponse(
                content,
                /* inputTokens = */ 0,
                /* outputTokens = */ 0,
                FIXTURE_PROVIDER,
                FIXTURE_MODEL,
                /* latencyMs = */ 0L,
                /* cached = */ false);
    }

    @Override
    public <T> T callEntity(String scene,
                            String prompt,
                            Class<T> responseType,
                            @Nullable String serviceId,
                            @Nullable String modelName,
                            @Nullable Duration timeoutOverride) {
        throw new UnsupportedOperationException(
                "FixtureBackedGenerationRouter 暂不支持 callEntity，如需请在场景测试层单独 mock");
    }

    @Override
    public ChatClientInfo getChatClientWithInfo(String scene,
                                                @Nullable String serviceId,
                                                @Nullable String modelName) {
        throw new UnsupportedOperationException(
                "FixtureBackedGenerationRouter 暂不支持 ChatClient 路径");
    }

    @Override
    public ChatModelInfo getChatModelWithInfo(String scene,
                                              @Nullable String serviceId,
                                              @Nullable String modelName) {
        return new ChatModelInfo(
                chatModel,
                FIXTURE_PROVIDER,
                FIXTURE_MODEL,
                ProviderType.OPENAI_COMPATIBLE,
                /* apiUrl = */ "http://fixture.local",
                /* supportsStreaming = */ false);
    }

    /**
     * 对外暴露内部 ChatModel 替身，便于场景测试断言或复用。
     */
    public FixtureBackedChatModel chatModel() {
        return chatModel;
    }

    @Override
    public StreamingGenerationResponse streamWithInfo(String scene,
                                                      String prompt,
                                                      @Nullable String serviceId,
                                                      @Nullable String modelName) {
        throw new UnsupportedOperationException(
                "FixtureBackedGenerationRouter 暂不支持流式生成");
    }

    @Override
    public int resolveMaxContextWindow(String scene,
                                       @Nullable String serviceId,
                                       @Nullable String modelName) {
        return 0;
    }

    /**
     * 从 prompt 文本里取最后一条用户消息（用于 fixture 匹配）。
     *
     * <p>当前约定：直接把整个 prompt 当作"最后一条用户消息"。原因是 {@link GenerationRouter#call}
     * 的 prompt 参数已经是拼装好的文本，没有结构化 message 数组。场景测试在定义 fixture 的
     * {@code last_user_contains} 正则时，应针对 prompt 的显著片段（而非完整文本）。</p>
     */
    private String extractLastUserMessage(String prompt) {
        return prompt == null ? "" : prompt;
    }
}
