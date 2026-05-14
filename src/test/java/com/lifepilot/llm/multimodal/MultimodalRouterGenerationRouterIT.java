package com.lifepilot.llm.multimodal;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.media.video.VideoProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import java.util.Map;

/**
 * MultimodalRouter 与 GenerationRouter 的端到端协作集成测试。
 *
 * <p>通过 Spring Context 加载真实 Bean，使用 MockitoBean 控制外部依赖，
 * 验证纯文本请求会委托给 GenerationRouter。</p>
 *
 * @author zsg
 * @since 2026-07-01
 */
@SpringBootTest
class MultimodalRouterGenerationRouterIT {

    @Autowired
    private MultimodalRouter multimodalRouter;

    @Autowired
    private ProviderRegistry providerRegistry;

    @Autowired
    private CircuitBreakerManager circuitBreakerManager;

    @Autowired
    private MediaProcessor mediaProcessor;

    @Autowired
    private MediaValidator mediaValidator;

    @Autowired
    private MediaProperties mediaProperties;

    @MockitoBean
    private GenerationRouter generationRouter;

    @MockitoBean
    private VideoProcessor videoProcessor;

    @Test
    void 纯文本请求委托给GenerationRouter() {
        MultimodalRequest request = new MultimodalRequest(
                "chat",
                "你好",
                List.of(),
                null
        );

        LlmResponse expected = new LlmResponse("hi", null, null, List.of(), Map.of(), 1, 1, null, 0, "p1", "m", 10, false);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(expected);

        LlmResponse actual = multimodalRouter.call(request);

        assertEquals(expected, actual);
        Mockito.verify(generationRouter).call(anyString(), anyString(), any(), any(), any(), any(), any());
    }
}
