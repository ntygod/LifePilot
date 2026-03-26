package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.multimodal.MediaContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringAiProviderAdapterTest {

    @Test
    void callWithMedia_requiresVisionCapability() {
        ProviderConfig config = providerConfig(Set.of(ProviderCapability.CHAT), "gpt");
        ChatModel chatModel = mock(ChatModel.class);
        SpringAiProviderAdapter adapter = new SpringAiProviderAdapter(config, chatModel, null, null);

        assertThrows(UnsupportedOperationException.class, () ->
                adapter.callWithMedia("hi", List.of(), Duration.ofSeconds(1)));
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

        SpringAiProviderAdapter adapter = new SpringAiProviderAdapter(config, chatModel, null, null);
        MediaContent image = new MediaContent(
                "id",
                "image/png",
                new byte[]{1, 2, 3},
                "a.png",
                3,
                Map.of()
        );

        LlmResponse llmResponse = adapter.callWithMedia("请看图", List.of(image), Duration.ofSeconds(1));

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

        SpringAiProviderAdapter adapter = new SpringAiProviderAdapter(config, chatModel, null, null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                adapter.call("slow", null, Duration.ofMillis(50)));
        assertNotNull(exception.getCause());
        assertTrue(exception.getCause() instanceof TimeoutException);
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

        String repaired = SpringAiProviderAdapter.repairJson(raw);
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

        String repaired = SpringAiProviderAdapter.repairJson(raw);
        var root = new ObjectMapper().readTree(repaired);

        assertTrue(root.path("decisions").isArray());
    }

    private ProviderConfig providerConfig(Set<ProviderCapability> capabilities, String modelName) {
        return new ProviderConfig(
                "p1",
                ProviderType.OPENAI_COMPATIBLE,
                "url",
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
}
