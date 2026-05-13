package com.lifepilot.workflow.engine;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.media.MediaProcessor;
import com.lifepilot.media.MediaValidator;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.media.video.VideoProcessor;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.WorkflowContext;
import com.lifepilot.workflow.model.WorkflowStep.LlmStep;
import com.lifepilot.workflow.model.WorkflowStep.MediaRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StepExecutorTest {

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private SkillActivator skillActivator;

    @Mock
    private DynamicToolRegistry toolRegistry;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private NotificationService notificationService;

    private StepExecutor executor;
    private ExpressionEngine expressionEngine;
    private MultimodalRouter multimodalRouter;
    private MultimodalRequest capturedMultimodalRequest;
    private LlmResponse multimodalResponse;

    @BeforeEach
    void setUp() {
        multimodalRouter = new MultimodalRouter(
                mock(ProviderRegistry.class),
                mock(CircuitBreakerManager.class),
                mock(MediaProcessor.class),
                mock(MediaValidator.class),
                mock(VideoProcessor.class),
                null,
                new MediaProperties(),
                generationRouter
        ) {
            @Override
            public LlmResponse call(MultimodalRequest request, Duration timeoutOverride) {
                capturedMultimodalRequest = request;
                return multimodalResponse;
            }
        };
        executor = new StepExecutor(
                skillRegistry,
                skillActivator,
                toolRegistry,
                generationRouter,
                multimodalRouter,
                new WorkflowConfigProperties(),
                notificationService
        );
        expressionEngine = new ExpressionEngine();
        capturedMultimodalRequest = null;
        multimodalResponse = null;
    }

    @Test
    void executeLlm_usesCapabilityRoutingAndParsesStructuredOutput() {
        WorkflowContext context = new WorkflowContext();
        context.set("inputs.topic", "事务");

        LlmStep step = new LlmStep(
                "llm-1",
                "Analyze",
                "code_generation",
                ProviderCapability.STRUCTURED_OUTPUT,
                "{\"topic\":\"${inputs.topic}\"}",
                "{\"type\":\"object\"}",
                "model-x",
                null,
                List.of(),
                List.of(),
                null,
                null
        );

        LlmResponse response = new LlmResponse("{\"answer\":\"ok\"}", null, null, List.of(), Map.of(), 12, 6, null, 0, "provider-1", "model-x", 18, false);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(), any(), any(Duration.class)))
                .thenReturn(response);

        Map<String, Object> result = executor.execute(step, context, expressionEngine);

        assertEquals("provider-1", result.get("providerId"));
        assertInstanceOf(Map.class, result.get("result"));
        assertEquals("ok", ((Map<?, ?>) result.get("result")).get("answer"));
        assertEquals(null, capturedMultimodalRequest);
    }

    @Test
    void executeLlm_withMedia_usesMultimodalRouter() {
        WorkflowContext context = new WorkflowContext();

        LlmStep step = new LlmStep(
                "vision-1",
                "Inspect",
                "content_review",
                ProviderCapability.VISION,
                "describe",
                "{\"type\":\"object\"}",
                null,
                "provider-vision",
                List.of(new MediaRef("data:image/png;base64,AQID", null, "upload.png")),
                List.of(),
                null,
                null
        );

        LlmResponse response = new LlmResponse("{\"label\":\"cat\"}", null, null, List.of(), Map.of(), 9, 3, null, 0, "provider-vision", "vision-model", 25, false);
        multimodalResponse = response;

        Map<String, Object> result = executor.execute(step, context, expressionEngine);

        MultimodalRequest request = capturedMultimodalRequest;
        assertEquals("content_review", request.scene());
        assertEquals(1, request.mediaList().size());
        assertEquals("image/png", request.mediaList().getFirst().mimeType());
        assertEquals("provider-vision", request.preferredProviderId());
        assertInstanceOf(Map.class, result.get("result"));
        assertEquals("cat", ((Map<?, ?>) result.get("result")).get("label"));
        verifyNoInteractions(generationRouter);
    }

    @Test
    void executeLlm_structuredOutputAcceptsJsonCodeFence() {
        WorkflowContext context = new WorkflowContext();

        LlmStep step = new LlmStep(
                "llm-json-fence",
                "Analyze",
                "agent_reasoning",
                ProviderCapability.STRUCTURED_OUTPUT,
                "analyze",
                "{\"type\":\"object\"}",
                null,
                null,
                List.of(),
                List.of(),
                null,
                null
        );

        LlmResponse response = new LlmResponse("```json\n{\"answer\":\"ok\"}\n```", null, null, List.of(), Map.of(), 11, 4, null, 0, "provider-1", "model-1", 12, false);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(), any(), any(Duration.class)))
                .thenReturn(response);

        Map<String, Object> result = executor.execute(step, context, expressionEngine);

        assertInstanceOf(Map.class, result.get("result"));
        assertEquals("ok", ((Map<?, ?>) result.get("result")).get("answer"));
    }

    @Test
    void executeLlm_structuredOutputInvalidJsonUsesReadableMessage() {
        WorkflowContext context = new WorkflowContext();

        LlmStep step = new LlmStep(
                "llm-invalid-json",
                "Analyze",
                "agent_reasoning",
                ProviderCapability.STRUCTURED_OUTPUT,
                "analyze",
                "{\"type\":\"object\"}",
                null,
                null,
                List.of(),
                List.of(),
                null,
                null
        );

        LlmResponse response = new LlmResponse("1、不是 JSON", null, null, List.of(), Map.of(), 11, 4, null, 0, "provider-1", "model-1", 12, false);
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(), any(), any(Duration.class)))
                .thenReturn(response);

        StepExecutor.WorkflowStepException exception = assertThrows(
                StepExecutor.WorkflowStepException.class,
                () -> executor.execute(step, context, expressionEngine)
        );

        assertTrue(exception.getMessage().contains("LLM 输出不是有效 JSON"));
    }
}
