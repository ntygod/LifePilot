package com.lifepilot.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolExecutionCoordinator 截图 → VISION 链路集成测试。
 *
 * <p>验证协调器对包含 screenshot Base64 字段的工具输出的完整处理：
 * <ul>
 *   <li>提取媒体 → 替换占位符 → pendingMedia 注入</li>
 *   <li>Base64 解码失败时的防御性降级</li>
 *   <li>非截图工具不触发媒体路径</li>
 * </ul>
 *
 * <p>防止 2026-03-08 实现的 MediaDataExtractor + replayExtractedMedia 链路被误改回归。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
class ToolExecutionCoordinator_截图vision链路测试 {

    /** MediaDataExtractor 识别 screenshot 字段要求长度超过 1000 字符。 */
    private static final int BASE64_PAYLOAD_LENGTH = 1500;

    private ObjectMapper objectMapper;
    private MediaDataExtractor mediaDataExtractor;
    private AgentToolProvider agentToolProvider;
    private MultimodalRouter multimodalRouter;

    @BeforeEach
    void 初始化() {
        objectMapper = new ObjectMapper();
        mediaDataExtractor = new MediaDataExtractor(objectMapper);
        agentToolProvider = mock(AgentToolProvider.class);
        multimodalRouter = mock(MultimodalRouter.class);
        when(agentToolProvider.resolveCanonicalToolId(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 截图成功时_应提取媒体并注入pendingMedia_observation包含占位符() throws Exception {
        // Given: 一张合法 base64 的 "截图"，超过 MediaDataExtractor 的最小阈值
        byte[] fakePng = "PNG-image-bytes".repeat(100).getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(fakePng);
        assertThat(base64).hasSizeGreaterThan(BASE64_PAYLOAD_LENGTH);

        String toolOutputJson = objectMapper.writeValueAsString(java.util.Map.of(
                "screenshot", base64,
                "url", "https://example.com/page",
                "fullPage", false
        ));

        when(agentToolProvider.resolveToolDisplayName("browser")).thenReturn("浏览器");
        when(multimodalRouter.isVisionAvailable()).thenReturn(true);

        var coordinator = 构造协调器(multimodalRouter);
        var callback = 工具回调("browser", toolOutputJson);
        var toolCall = new AssistantMessage.ToolCall(
                "call-screenshot",
                "function",
                "browser",
                "{\"url\":\"https://example.com/page\"}"
        );

        // When
        var resultState = coordinator.execute(
                基础状态(),
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        // Then: pendingMedia 非空且含一条 image/png MediaContent
        assertThat(resultState.pendingMedia())
                .as("截图成功后 pendingMedia 应被注入")
                .isNotNull()
                .hasSize(1);
        MediaContent mediaContent = resultState.pendingMedia().getFirst();
        assertThat(mediaContent.mimeType()).isEqualTo("image/png");
        assertThat(mediaContent.data())
                .as("pendingMedia 内应为解码后的原始字节")
                .isEqualTo(fakePng);
        assertThat(mediaContent.metadata())
                .containsEntry("toolId", "browser")
                .containsEntry("fieldName", "screenshot");

        // Then: Observation 步骤中含 MediaDataExtractor 的占位符文本
        var observation = resultState.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation)
                .map(s -> (ReactStep.Observation) s)
                .findFirst()
                .orElseThrow();
        assertThat(observation.success()).isTrue();
        assertThat(observation.output())
                .as("占位符应替换掉原始 base64 数据避免污染 LLM 上下文")
                .contains(MediaDataExtractor.PLACEHOLDER)
                .doesNotContain(base64);
    }

    @Test
    void Base64解码失败时_不抛异常且pendingMedia保持空() throws Exception {
        // Given: screenshot 字段超过长度阈值（能被 MediaDataExtractor 捕获）但不是合法 base64
        // 利用非 base64 字符（含 !）制造解码失败
        String invalidBase64 = "not-a-valid-base64-!!!".repeat(100);
        assertThat(invalidBase64).hasSizeGreaterThan(BASE64_PAYLOAD_LENGTH);

        String toolOutputJson = objectMapper.writeValueAsString(java.util.Map.of(
                "screenshot", invalidBase64,
                "url", "https://example.com/broken"
        ));

        when(agentToolProvider.resolveToolDisplayName("browser")).thenReturn("浏览器");
        when(multimodalRouter.isVisionAvailable()).thenReturn(true);

        var coordinator = 构造协调器(multimodalRouter);
        var callback = 工具回调("browser", toolOutputJson);
        var toolCall = new AssistantMessage.ToolCall(
                "call-broken",
                "function",
                "browser",
                "{\"url\":\"https://example.com/broken\"}"
        );

        // When / Then: 不抛异常
        var resultState = coordinator.execute(
                基础状态(),
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        // Then: pendingMedia 保持空（解码失败被吞掉）
        assertThat(resultState.pendingMedia())
                .as("Base64 解码失败时不应把损坏数据塞入 pendingMedia")
                .isNullOrEmpty();

        // Then: Observation 仍然被记录（不影响主链路）
        assertThat(resultState.steps())
                .anyMatch(s -> s instanceof ReactStep.Observation obs && obs.success());
    }

    @Test
    void 非截图工具返回类似字段时_不应进入pendingMedia路径() throws Exception {
        // Given: 一个 web.fetch 风格的工具返回类似 JSON，但字段名与媒体提取器无关
        // 注意：MediaDataExtractor 的识别基于字段名 "screenshot"，所以这里即便带了类似 screenshot 的字段，
        // 只要工具不返回该字段，就不会进入媒体路径。
        String toolOutputJson = objectMapper.writeValueAsString(java.util.Map.of(
                "body", "<html>fake page</html>".repeat(200),
                "status", 200,
                "url", "https://example.com/fetch"
        ));

        when(agentToolProvider.resolveToolDisplayName("web.fetch")).thenReturn("网页获取");
        when(multimodalRouter.isVisionAvailable()).thenReturn(true);

        var coordinator = 构造协调器(multimodalRouter);
        var callback = 工具回调("web.fetch", toolOutputJson);
        var toolCall = new AssistantMessage.ToolCall(
                "call-fetch",
                "function",
                "web.fetch",
                "{\"url\":\"https://example.com/fetch\"}"
        );

        // When
        var resultState = coordinator.execute(
                基础状态(),
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        // Then: pendingMedia 不应注入
        assertThat(resultState.pendingMedia())
                .as("非截图工具（无 screenshot 字段）不应触发媒体路径")
                .isNullOrEmpty();

        // Then: Observation 原样携带工具输出，不插入占位符
        var observation = resultState.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation)
                .map(s -> (ReactStep.Observation) s)
                .findFirst()
                .orElseThrow();
        assertThat(observation.output())
                .as("无媒体字段的输出应保持原样")
                .doesNotContain(MediaDataExtractor.PLACEHOLDER)
                .doesNotContain(MediaDataExtractor.NO_VISION_PLACEHOLDER);
    }

    @Test
    void VISION不可用时_截图字段触发占位符替换但pendingMedia不注入() throws Exception {
        // Given: 截图成功但 VISION Provider 不可用
        byte[] fakePng = "PNG-bytes".repeat(150).getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(fakePng);
        assertThat(base64).hasSizeGreaterThan(BASE64_PAYLOAD_LENGTH);

        String toolOutputJson = objectMapper.writeValueAsString(java.util.Map.of(
                "screenshot", base64,
                "url", "https://example.com/no-vision"
        ));

        when(agentToolProvider.resolveToolDisplayName("browser")).thenReturn("浏览器");
        when(multimodalRouter.isVisionAvailable()).thenReturn(false);

        var coordinator = 构造协调器(multimodalRouter);
        var callback = 工具回调("browser", toolOutputJson);
        var toolCall = new AssistantMessage.ToolCall(
                "call-no-vision",
                "function",
                "browser",
                "{\"url\":\"https://example.com/no-vision\"}"
        );

        // When
        var resultState = coordinator.execute(
                基础状态(),
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        // Then: VISION 不可用时跳过 pendingMedia 注入
        assertThat(resultState.pendingMedia())
                .as("VISION 不可用时不注入 pendingMedia，避免后续迭代强制走视觉路由")
                .isNullOrEmpty();

        // Then: observation 使用 NO_VISION_PLACEHOLDER 引导 Agent 走文本工具
        var observation = resultState.steps().stream()
                .filter(s -> s instanceof ReactStep.Observation)
                .map(s -> (ReactStep.Observation) s)
                .findFirst()
                .orElseThrow();
        assertThat(observation.output())
                .contains(MediaDataExtractor.NO_VISION_PLACEHOLDER)
                .doesNotContain(base64);
    }

    // ===== 辅助方法 =====

    private ToolExecutionCoordinator 构造协调器(MultimodalRouter router) {
        return new ToolExecutionCoordinator(
                agentToolProvider,
                objectMapper,
                null,                    // traceRecorder
                null,                    // transcriptStore
                mediaDataExtractor,
                null,                    // proceduralMemory
                null,                    // intentMatcher
                4,                       // maxParallelToolCalls
                router,
                null                     // workspaceService
        );
    }

    private ReactAgentState 基础状态() {
        var budget = Budget.builder()
                .maxTokens(4096)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("截图链路测试", "session-screenshot", "web", null, null,
                budget, null, 0, null, null, null, null);
        return ReactAgentState.init(request, budget);
    }

    private ToolCallback 工具回调(String toolName, String returnJson) {
        return new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name(toolName)
                    .description(toolName)
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return returnJson;
            }
        };
    }
}
