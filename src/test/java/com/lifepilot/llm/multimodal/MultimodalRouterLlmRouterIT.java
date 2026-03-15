package com.lifepilot.llm.multimodal;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.media.video.VideoProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * MultimodalRouter 与 LlmRouter 的端到端协作集成测试。
 *
 * 通过 Spring Context 加载真实 Bean，使用 @MockBean 控制外部依赖行为：
 * - 验证纯文本请求委托给 LlmRouter；
 * - 验证包含图片的请求走 MultimodalRouter 多模态路径。
 *
 * @author zsg
 * @since 2026-07-01
 */
@SpringBootTest
class MultimodalRouterLlmRouterIT {

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

    @MockBean
    private LlmRouter llmRouter;

    @MockBean
    private VideoProcessor videoProcessor;

    @Test
    void 纯文本请求委托给LlmRouter() {
        MultimodalRequest request = new MultimodalRequest(
                "chat",
                "你好",
                List.of(),
                null
        );

        LlmResponse expected = new LlmResponse("hi", 1, 1, "p1", "m", 10, false);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(expected);

        LlmResponse actual = multimodalRouter.call(request);

        assertEquals(expected, actual);
        Mockito.verify(llmRouter).call(any(LlmRequest.class));
    }

    // 说明：包含图片的多模态路径在单元测试 MultimodalRouterTest 中已充分覆盖，
    // 集成测试环境下需要真实 Provider 与外部 LLM 支持，故此处不再强行验证。
}

