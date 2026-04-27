package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.multimodal.MediaContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AbstractProviderAdapter 单元测试。
 *
 * <p>原 SpringAiProviderAdapter 单类被 Phase 3 重构为抽象基类 + 多态子类，本测试通过
 * 私有 {@link TestAdapter} 实例化抽象基类的全部行为，覆盖与原测试一致的语义。
 *
 * @author zsg
 */
@ExtendWith(MockitoExtension.class)
class AbstractProviderAdapterTest {

    @Test
    void callWithMedia_requiresVisionCapability() {
        ProviderConfig config = providerConfig(Set.of(ProviderCapability.CHAT), "gpt");
        ChatModel chatModel = mock(ChatModel.class);
        AbstractProviderAdapter adapter = new TestAdapter(config, chatModel, null, null);

        assertThrows(UnsupportedOperationException.class, () ->
                adapter.callWithMedia("hi", List.of(), null, Duration.ofSeconds(1)));
    }

    @Test
    void callWithMedia_returnsLlmResponse() {
        ProviderConfig config = providerConfig(Set.of(ProviderCapability.CHAT, ProviderCapability.VISION), "gpt-vision");
        ChatModel chatModel = mock(ChatModel.class);

        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getMetadata().getUsage().getPromptTokens()).thenReturn(5);
        when(response.getMetadata().getUsage().getCompletionTokens()).thenReturn(7);
        when(response.getResult().getOutput().getText()).thenReturn("ok");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        AbstractProviderAdapter adapter = new TestAdapter(config, chatModel, null, null);
        MediaContent image = new MediaContent(
                "id",
                "image/png",
                new byte[]{1, 2, 3},
                "a.png",
                3,
                Map.of()
        );

        LlmResponse llmResponse = adapter.callWithMedia("请看图", List.of(image), null, Duration.ofSeconds(1));

        assertEquals("ok", llmResponse.content());
        assertEquals(5, llmResponse.inputTokens());
        assertEquals(7, llmResponse.outputTokens());
        assertEquals("p1", llmResponse.providerId());
        assertEquals("gpt-vision", llmResponse.modelName());
    }

    @Test
    void call_respectsProvidedTimeout() {
        ProviderConfig config = providerConfig(Set.of(ProviderCapability.CHAT), "gpt");
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(200);
            return mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        });

        AbstractProviderAdapter adapter = new TestAdapter(config, chatModel, null, null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                adapter.call("slow", null, Duration.ofMillis(50)));
        assertNotNull(exception.getCause());
        assertTrue(exception.getCause() instanceof TimeoutException);
    }

    @Test
    void call_对OpenAi兼容模型下发协议级JsonSchema() {
        String outputSchema = """
                {"type":"object","properties":{"answer":{"type":"string"}}}
                """;
        ProviderConfig config = providerConfig(
                Set.of(ProviderCapability.CHAT),
                "gpt-4.1",
                "https://api.openai.com/v1"
        );
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);

        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn("{\"answer\":\"ok\"}");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        AbstractProviderAdapter adapter = new TestAdapter(config, chatModel, null, null);

        adapter.call("请按 schema 输出", outputSchema, Duration.ofSeconds(1));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
        assertEquals(Boolean.TRUE, options.getResponseFormat().getJsonSchema().getStrict());
        assertEquals("object", options.getResponseFormat().getJsonSchema().getSchema().get("type"));
    }

    @Test
    void stream_对OpenAi兼容模型开启Usage回传() {
        ProviderConfig config = providerConfig(
                Set.of(ProviderCapability.CHAT, ProviderCapability.STREAMING),
                "gpt-4.1",
                "https://api.openai.com/v1"
        );
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        var generation = new Generation(new AssistantMessage("ok"));
        var response = new ChatResponse(List.of(generation));
        doReturn(Flux.just(response)).when(chatModel).stream(any(Prompt.class));

        AbstractProviderAdapter adapter = new TestAdapter(config, chatModel, null, null);

        assertEquals(List.of("ok"), adapter.stream("hello").collectList().block());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(Boolean.TRUE, options.getStreamUsage());
    }

    @Test
    void call_对DeepSeek应回退为jsonObject并附带Schema提示词() {
        String outputSchema = """
                {"type":"object","properties":{"summary":{"type":"string"}}}
                """;
        ProviderConfig config = providerConfig(
                Set.of(ProviderCapability.CHAT),
                "deepseek-chat",
                "https://api.deepseek.com/v1"
        );
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);

        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn("{\"summary\":\"ok\"}");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        AbstractProviderAdapter adapter = new TestAdapter(config, chatModel, null, null);

        adapter.call("请按 schema 输出", outputSchema, Duration.ofSeconds(1));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
        assertTrue(prompt.getContents().contains("请仅输出一个合法 JSON 对象"));
        assertTrue(prompt.getContents().contains("<json_schema>"));
    }

    @Test
    void repairJson_shouldEscapeEmbeddedChineseQuotes() throws Exception {
        String raw = """
                {
                  "decisions": [
                    {
                      "description": "用户希望被称呼为"老板"，用户称呼 AI 为"微微"",
                      "entityName": "用户称呼偏好",
                      "entityType": "PREFERENCE",
                      "operation": "UPDATE",
                      "properties": {}
                    }
                  ]
                }
                """;

        String repaired = AbstractProviderAdapter.repairJson(raw);
        var root = new ObjectMapper().readTree(repaired);

        assertEquals("用户希望被称呼为\"老板\"，用户称呼 AI 为\"微微\"",
                root.at("/decisions/0/description").asText());
    }

    @Test
    void repairJson_shouldStripMarkdownFence() throws Exception {
        String raw = """
                ```json
                {
                  "decisions": []
                }
                ```
                """;

        String repaired = AbstractProviderAdapter.repairJson(raw);
        var root = new ObjectMapper().readTree(repaired);

        assertTrue(root.path("decisions").isArray());
    }

    private ProviderConfig providerConfig(Set<ProviderCapability> capabilities, String modelName) {
        return providerConfig(capabilities, modelName, "https://api.openai.com/v1");
    }

    private ProviderConfig providerConfig(Set<ProviderCapability> capabilities, String modelName, String apiUrl) {
        return new ProviderConfig(
                "p1",
                ProviderType.OPENAI_COMPATIBLE,
                "openai-official",
                apiUrl,
                null,
                modelName,
                30,
                0,
                List.of("chat"),
                capabilities,
                true,
                0,
                0,
                8192,
                null,
                true
        );
    }

    /**
     * 测试用具体子类 — 仅暴露 AbstractProviderAdapter 行为，不引入额外语义。
     */
    private static final class TestAdapter extends AbstractProviderAdapter {
        TestAdapter(ProviderConfig config,
                    ChatModel chatModel,
                    @Nullable EmbeddingModel embeddingModel,
                    @Nullable List<CallAdvisor> defaultAdvisors) {
            super(config, chatModel, embeddingModel, defaultAdvisors);
        }

        @Override
        public Flux<com.lifepilot.llm.stream.LlmStreamEvent> streamEvents(
                Prompt prompt, List<org.springframework.ai.tool.ToolCallback> toolCallbacks) {
            return chatModel.stream(prompt).flatMap(this::chunkToEvents);
        }
    }
}
