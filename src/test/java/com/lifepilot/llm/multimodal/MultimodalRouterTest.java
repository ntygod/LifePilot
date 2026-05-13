package com.lifepilot.llm.multimodal;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.llm.thinking.ThinkingMode;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.media.video.VideoProcessResult;
import com.lifepilot.media.video.VideoProcessor;
import com.lifepilot.modelservice.model.GenerationCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MultimodalRouter 单元测试。
 *
 * 覆盖纯文本委托、VISION 过滤与故障转移、流式调用以及视频预处理集成路径。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class MultimodalRouterTest {

    @Mock
    private ProviderRegistry providerRegistry;

    @Mock
    private CircuitBreakerManager circuitBreakerManager;

    @Mock
    private MediaProcessor mediaProcessor;

    @Mock
    private MediaValidator mediaValidator;

    @Mock
    private VideoProcessor videoProcessor;

    @Mock
    private GenerationRouter generationRouter;

    private MultimodalRouter router;

    @BeforeEach
    void setUp() {
        router = new MultimodalRouter(
                providerRegistry,
                circuitBreakerManager,
                mediaProcessor,
                mediaValidator,
                videoProcessor,
                null,
                new MediaProperties(),
                generationRouter
        );
    }

    @Test
    void 无图片附件时委托给LlmRouter处理纯文本请求() {
        MultimodalRequest request = new MultimodalRequest(
                "chat",
                "你好",
                List.of(),
                null
        );

        LlmResponse expected = new LlmResponse("hi", null, null, List.of(), Map.of(), 1, 1, null, 0, "p1", "m", 10, false);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(expected);

        LlmResponse actual = router.call(request);

        assertSame(expected, actual);
        verify(generationRouter).call(anyString(), anyString(), any(), any(), any(), any(), any());
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void 无图片附件且有输出Schema时按结构化能力委托() {
        String outputSchema = """
                {"type":"object","properties":{"answer":{"type":"string"}}}
                """;
        MultimodalRequest request = new MultimodalRequest(
                "chat",
                "请输出结构化结果",
                List.of(),
                outputSchema
        );

        LlmResponse expected = new LlmResponse("{}", null, null, List.of(), Map.of(), 1, 1, null, 0, "p1", "m", 10, false);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(expected);

        LlmResponse actual = router.call(request);

        assertSame(expected, actual);
        verify(generationRouter).call(
                eq("chat"),
                eq("请输出结构化结果"),
                eq(outputSchema),
                isNull(),
                isNull(),
                eq(GenerationCapability.STRUCTURED_OUTPUT),
                isNull()
        );
    }

    @Test
    void 有图片附件时执行VISION过滤并调用ProviderAdapter() {
        String outputSchema = """
                {"type":"object","properties":{"summary":{"type":"string"}}}
                """;
        MediaContent image = new MediaContent(
                "img1",
                "image/png",
                new byte[] {1, 2},
                "a.png",
                2,
                Map.of()
        );
        MultimodalRequest request = new MultimodalRequest(
                "vision-scene",
                "看一下这张图片",
                List.of(image),
                outputSchema
        );

        ProviderConfig visionProvider = new ProviderConfig(
                "vision-1",
                "openai-official",
                "url",
                null,
                "gpt-4-vision",
                30,
                1,
                List.of("vision-scene"),
                Set.of(ProviderCapability.CHAT, ProviderCapability.VISION),
                true,
                0,
                0,
                8192,
                null,
                true,
                false,
                ThinkingMode.AUTO
        );

        when(providerRegistry.findByScene("vision-scene")).thenReturn(List.of(visionProvider));
        when(circuitBreakerManager.isCallPermitted("vision-1", "VISION")).thenReturn(true);

        // mediaProcessor.processAll 直接返回入参列表
        when(mediaProcessor.processAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        // 使用真实的 AbstractProviderAdapter 子类替身无法轻量构造，这里 mock 抽象基类
        // ProviderAdapter 是 sealed interface 无法直接 mock，AbstractProviderAdapter 是 non-sealed 可 mock。
        var adapter = mock(com.lifepilot.llm.adapter.AbstractProviderAdapter.class);
        when(providerRegistry.getAdapter("vision-1")).thenReturn(adapter);

        LlmResponse response = new LlmResponse("answer", null, null, List.of(), Map.of(), 10, 20, null, 0, "vision-1", "gpt", 100, false);
        when(adapter.callWithMedia(anyString(), anyList(), any(), any(Duration.class))).thenReturn(response);

        LlmResponse actual = router.call(request);

        assertSame(response, actual);
        verify(mediaValidator).validateAll(anyList());
        verify(mediaProcessor).processAll(anyList());
        verify(adapter).callWithMedia(eq("看一下这张图片"), anyList(), eq(outputSchema), any(Duration.class));
        verify(circuitBreakerManager).recordSuccess("vision-1", "VISION");
    }

    @Test
    void 所有VISION候选失败时抛出LlmUnavailableException() {
        MediaContent image = new MediaContent(
                "img1",
                "image/png",
                new byte[] {1, 2},
                "a.png",
                2,
                Map.of()
        );
        MultimodalRequest request = new MultimodalRequest(
                "vision-scene",
                "看一下这张图片",
                List.of(image),
                null
        );

        ProviderConfig visionProvider = new ProviderConfig(
                "vision-1",
                "openai-official",
                "url",
                null,
                "gpt-4-vision",
                30,
                1,
                List.of("vision-scene"),
                Set.of(ProviderCapability.CHAT, ProviderCapability.VISION),
                true,
                0,
                0,
                8192,
                null,
                true,
                false,
                ThinkingMode.AUTO
        );

        when(providerRegistry.findByScene("vision-scene")).thenReturn(List.of(visionProvider));
        when(circuitBreakerManager.isCallPermitted("vision-1", "VISION")).thenReturn(true);
        when(mediaProcessor.processAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var adapter = mock(com.lifepilot.llm.adapter.AbstractProviderAdapter.class);
        when(providerRegistry.getAdapter("vision-1")).thenReturn(adapter);
        when(adapter.callWithMedia(anyString(), anyList(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("下游异常"));

        assertThrows(LlmUnavailableException.class, () -> router.call(request));
        verify(circuitBreakerManager).recordFailure("vision-1", "VISION");
    }

    @Test
    void stream_无图片时委托LlmRouterstream() {
        MultimodalRequest request = new MultimodalRequest(
                "chat",
                "hello",
                List.of(),
                null
        );

        when(generationRouter.streamWithInfo("chat", "hello", (String) null, (String) null))
                .thenReturn(new GenerationRouter.StreamingGenerationResponse(Flux.just("a", "b"), "p1", "m1"));

        Flux<String> flux = router.stream(request);

        assertEquals(List.of("a", "b"), flux.collectList().block());
        verify(generationRouter).streamWithInfo("chat", "hello", (String) null, (String) null);
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void 包含视频时先经过VideoProcessor预处理并追加转录文本() {
        MediaContent video = new MediaContent(
                "v1",
                "video/mp4",
                new byte[] {1, 2, 3},
                "video.mp4",
                3,
                Map.of()
        );
        MultimodalRequest request = new MultimodalRequest(
                "vision-scene",
                "原始文本",
                List.of(video),
                null
        );

        // VideoProcessor 返回两个关键帧和转录文本
        MediaContent frame1 = new MediaContent("f1", "image/jpeg", new byte[] {9}, "f1.jpg", 1, Map.of());
        MediaContent frame2 = new MediaContent("f2", "image/jpeg", new byte[] {8}, "f2.jpg", 1, Map.of());
        VideoProcessResult result = new VideoProcessResult(List.of(frame1, frame2), "这是转录", 10, 2);
        when(videoProcessor.process(any(), eq("video/mp4"))).thenReturn(result);

        ProviderConfig visionProvider = new ProviderConfig(
                "vision-1",
                "openai-official",
                "url",
                null,
                "gpt-4-vision",
                30,
                1,
                List.of("vision-scene"),
                Set.of(ProviderCapability.CHAT, ProviderCapability.VISION),
                true,
                0,
                0,
                8192,
                null,
                true,
                false,
                ThinkingMode.AUTO
        );

        when(providerRegistry.findByScene("vision-scene")).thenReturn(List.of(visionProvider));
        when(circuitBreakerManager.isCallPermitted("vision-1", "VISION")).thenReturn(true);
        when(mediaProcessor.processAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var adapter = mock(com.lifepilot.llm.adapter.AbstractProviderAdapter.class);
        when(providerRegistry.getAdapter("vision-1")).thenReturn(adapter);
        when(adapter.callWithMedia(anyString(), anyList(), any(), any(Duration.class)))
                .thenReturn(new LlmResponse("ok", null, null, List.of(), Map.of(), 0, 0, null, 0, "vision-1", "m", 1, false));

        router.call(request);

        // 捕获传入 adapter 的 prompt，验证包含转录文本
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(adapter).callWithMedia(promptCaptor.capture(), anyList(), isNull(), any(Duration.class));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("原始文本"));
        assertTrue(prompt.contains("视频音轨转录"), "应包含转录标题");
        assertTrue(prompt.contains("这是转录"));
    }
}
