package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.callback.IterationCallback;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.ProviderMessageBuilder;
import com.lifepilot.agent.context.TokenBudget;
import com.lifepilot.agent.context.TranscriptHygieneEngine;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReactAgentLoop 截图 → 视觉迭代集成测试。
 *
 * <p>验证完整链路：
 * <ol>
 *   <li>第 1 轮 LLM 产生 browser 截图 tool call</li>
 *   <li>ToolExecutionCoordinator 提取 base64 → 注入 pendingMedia</li>
 *   <li>第 2 轮 LLM 能在 effectiveRequest.mediaContents() 看到该 MediaContent</li>
 *   <li>第 2 轮 LLM 调用后 pendingMedia 被清空（避免后续迭代重复嵌入）</li>
 * </ol>
 *
 * <p>VISION 不可用场景覆盖：ToolExecutionCoordinator 会跳过 pendingMedia 注入，
 * 改用 NO_VISION_PLACEHOLDER 引导 Agent 使用纯文本工具。</p>
 *
 * <p>防止 2026-03-08 实现的 pendingMedia + L319-322 清空逻辑被误改回归。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class ReactAgentLoop_截图视觉迭代测试 {

    @Mock
    private ContextAssembler contextAssembler;

    @Mock
    private AgentToolProvider agentToolProvider;

    @Mock
    private SharedScheduler sharedScheduler;

    @Mock
    private TranscriptStore transcriptStore;

    @Mock
    private MultimodalRouter multimodalRouter;

    private ObjectMapper objectMapper;
    private MediaDataExtractor mediaDataExtractor;

    @BeforeEach
    void 初始化() {
        objectMapper = new ObjectMapper();
        mediaDataExtractor = new MediaDataExtractor(objectMapper);
        ScheduledExecutorService scheduledExecutor = mock(ScheduledExecutorService.class);
        when(sharedScheduler.cleanup()).thenReturn(scheduledExecutor);
    }

    @Test
    void VISION可用时_截图tool结果应在第二轮LLM调用时出现在mediaContents中() throws Exception {
        // Given: VISION 可用 → ToolExecutionCoordinator 会注入 pendingMedia
        when(multimodalRouter.isVisionAvailable()).thenReturn(true);
        when(contextAssembler.assemble(any())).thenReturn(基础上下文("打开网页并截图分析"));
        when(agentToolProvider.resolveToolDisplayName("browser")).thenReturn("浏览器");
        when(agentToolProvider.resolveCanonicalToolId("browser")).thenReturn("browser");

        byte[] fakePng = "PNG-bytes".repeat(200).getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(fakePng);
        // 必须 > MediaDataExtractor.MIN_MEDIA_LENGTH（1000）才会被识别为媒体
        assertThat(base64).hasSizeGreaterThan(1000);

        String screenshotJson = objectMapper.writeValueAsString(java.util.Map.of(
                "screenshot", base64,
                "url", "https://example.com",
                "fullPage", false
        ));

        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                工具回调("browser", screenshotJson)
        ));

        var loop = 构造循环(multimodalRouter, mediaDataExtractor);

        // When: fake LLM callback 记录每轮的 mediaContents 快照
        var llmCallCount = new AtomicInteger();
        var mediaContentsPerCall = new ArrayList<List<MediaContent>>();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            int call = llmCallCount.getAndIncrement();
            // 快照当前轮次 effectiveRequest.mediaContents（可能为 null）
            var snapshot = agentRequest.mediaContents();
            mediaContentsPerCall.add(snapshot != null ? List.copyOf(snapshot) : List.of());
            if (call == 0) {
                // 第 1 轮：返回截图 tool call
                var toolCall = new AssistantMessage.ToolCall(
                        "call-shot-1", "function", "browser", "{\"url\":\"https://example.com\"}");
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("先截图看看").toolCalls(List.of(toolCall)).build())));
            }
            // 第 2 轮：返回最终回答
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("我看到页面了，这是首页。"))));
        };

        var budget = 基础预算();
        var request = 简单请求("打开网页并截图分析", "session-shot-vision", budget);
        var initialState = ReactAgentState.init(request, budget);

        var result = loop.coreLoop(
                initialState, request, null, Instant.now(),
                callback, new CancellationToken(), new AgentLoopContext()
        );

        // Then: 两轮 LLM 调用
        assertThat(llmCallCount.get()).isEqualTo(2);

        // Then: 第 1 轮的 effectiveRequest 无 mediaContents（无用户上传图片，也无 pendingMedia）
        assertThat(mediaContentsPerCall.get(0))
                .as("第 1 轮 LLM 调用前尚未有截图，mediaContents 应为空")
                .isEmpty();

        // Then: 第 2 轮的 effectiveRequest.mediaContents() 非空，含截图 MediaContent
        assertThat(mediaContentsPerCall.get(1))
                .as("第 2 轮 LLM 能看到上一轮截图的 pendingMedia")
                .isNotEmpty();
        var viewedMedia = mediaContentsPerCall.get(1).getFirst();
        assertThat(viewedMedia.mimeType()).isEqualTo("image/png");
        assertThat(viewedMedia.data()).isEqualTo(fakePng);

        // Then: 循环结束后 state.pendingMedia() 已清空（L319-322 清空逻辑）
        assertThat(result.pendingMedia())
                .as("pendingMedia 应在第 2 轮 LLM 调用后被清空，避免第 3 轮重复嵌入")
                .isNullOrEmpty();

        // Then: 最终回答正常返回
        assertThat(result.finalOutput()).isEqualTo("我看到页面了，这是首页。");
    }

    @Test
    void VISION不可用时_不注入pendingMedia且observation使用NoVision占位符() throws Exception {
        // Given: VISION 不可用
        when(multimodalRouter.isVisionAvailable()).thenReturn(false);
        when(contextAssembler.assemble(any())).thenReturn(基础上下文("截图但无视觉模型"));
        when(agentToolProvider.resolveToolDisplayName("browser")).thenReturn("浏览器");
        when(agentToolProvider.resolveCanonicalToolId("browser")).thenReturn("browser");

        byte[] fakePng = "PNG".repeat(400).getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(fakePng);
        assertThat(base64).hasSizeGreaterThan(1000);

        String screenshotJson = objectMapper.writeValueAsString(java.util.Map.of(
                "screenshot", base64,
                "url", "https://example.com/no-vision"
        ));
        when(agentToolProvider.getToolCallbacks(any(), nullable(String.class), any())).thenReturn(List.of(
                工具回调("browser", screenshotJson)
        ));

        var loop = 构造循环(multimodalRouter, mediaDataExtractor);

        // When
        var llmCallCount = new AtomicInteger();
        var pendingMediaAtSecondCall = new AtomicReference<List<MediaContent>>();
        IterationCallback callback = (agentRequest, messages, toolCallbacks, traceContext) -> {
            int call = llmCallCount.getAndIncrement();
            if (call == 0) {
                var toolCall = new AssistantMessage.ToolCall(
                        "call-shot-nv", "function", "browser", "{\"url\":\"https://example.com/no-vision\"}");
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
            }
            // 记录第 2 轮时 request 上的 mediaContents
            var snapshot = agentRequest.mediaContents();
            pendingMediaAtSecondCall.set(snapshot != null ? List.copyOf(snapshot) : List.of());
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("我没法直接看图，但已经截好了。"))));
        };

        var budget = 基础预算();
        var request = 简单请求("截图但无视觉模型", "session-shot-no-vision", budget);
        var initialState = ReactAgentState.init(request, budget);

        var result = loop.coreLoop(
                initialState, request, null, Instant.now(),
                callback, new CancellationToken(), new AgentLoopContext()
        );

        // Then: 第 2 轮 LLM 的 mediaContents 为空（未注入 pendingMedia）
        assertThat(pendingMediaAtSecondCall.get())
                .as("VISION 不可用时不注入 pendingMedia")
                .isEmpty();

        // Then: Observation 步骤带 NO_VISION_PLACEHOLDER，引导 Agent 改用文本工具
        var observation = result.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation obs && "browser".equals(obs.toolId()))
                .map(s -> (ReactStep.Observation) s)
                .findFirst()
                .orElseThrow();
        assertThat(observation.output())
                .contains(MediaDataExtractor.NO_VISION_PLACEHOLDER);

        // Then: 循环正常完成
        assertThat(result.finalOutput()).isEqualTo("我没法直接看图，但已经截好了。");
    }

    // ===== 辅助方法 =====

    private ReactAgentLoop 构造循环(MultimodalRouter router, MediaDataExtractor extractor) {
        return new ReactAgentLoop(
                contextAssembler,
                new ProviderMessageBuilder(new TranscriptHygieneEngine(new AgentConfigProperties())),
                agentToolProvider,
                new AgentConfigProperties(),
                objectMapper,
                null,                 // traceRecorder
                transcriptStore,
                router,                // multimodalRouter
                extractor,             // mediaDataExtractor
                null,                 // eventPublisher
                null,                 // proceduralMemory
                null,                 // intentMatcher
                null,                 // compactionEngine
                sharedScheduler,
                null,                 // workspaceService
                null                  // experienceSummarizer
        );
    }

    private Budget 基础预算() {
        return Budget.builder()
                .maxTokens(32000)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(8)
                .stepsUsed(0)
                .maxDuration(Duration.ofSeconds(120))
                .elapsed(Duration.ZERO)
                .build();
    }

    private AssembledContext 基础上下文(String userPrompt) {
        return new AssembledContext(
                "你是测试助手",
                List.of(),
                List.of(),
                userPrompt,
                List.of(),
                TokenBudget.allocateDefault(4096),
                0,
                0.0f,
                0,
                false,
                List.of(),
                null
        );
    }

    private AgentRequest 简单请求(String message, String sessionId, Budget budget) {
        return new AgentRequest(message, sessionId, "web", null, null, budget, null, 0, null, null, null, null);
    }

    private ToolCallback 工具回调(String name, String returnValue) {
        return new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name(name)
                    .description(name)
                    .inputSchema("{}")
                    .build();

            @Override
            @NonNull
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput) {
                return returnValue;
            }
        };
    }
}
