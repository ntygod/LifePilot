package com.lifepilot.llm.adapter;

import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.multimodal.MediaContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SpringAiProviderAdapter 单元测试。
 *
 * 覆盖 VISION 能力检查和多模态调用 Media 构建逻辑。
 *
 * @author zsg
 * @since 2026-07-01
 */
@ExtendWith(MockitoExtension.class)
class SpringAiProviderAdapterTest {

    @Test
    void 不含VISION能力时调用callWithMedia抛出异常() {
        ProviderConfig config = new ProviderConfig(
                "p1",
                com.lifepilot.llm.config.ProviderType.OPENAI_COMPATIBLE,
                "url",
                null,
                "gpt",
                30,
                0,
                List.of("chat"),
                Set.of(ProviderCapability.CHAT),
                true,
                0,
                0,
                8192,
                null,
                true
        );

        ChatModel chatModel = mock(ChatModel.class);

        SpringAiProviderAdapter adapter = new SpringAiProviderAdapter(
                config,
                chatModel,
                null,
                null
        );

        assertThrows(UnsupportedOperationException.class, () ->
                adapter.callWithMedia("hi", List.of(), Duration.ofSeconds(1)));
    }

    @Test
    void 含VISION能力时多模态调用成功并返回LlmResponse() {
        ProviderConfig config = new ProviderConfig(
                "p1",
                com.lifepilot.llm.config.ProviderType.OPENAI_COMPATIBLE,
                "url",
                null,
                "gpt-vision",
                30,
                0,
                List.of("chat"),
                Set.of(ProviderCapability.CHAT, ProviderCapability.VISION),
                true,
                0,
                0,
                8192,
                null,
                true
        );

        ChatModel chatModel = mock(ChatModel.class);

        // 使用深度 stub 构造 ChatResponse
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        // 使用深度 stub，直接在调用点上配置 getUsage 结果，避免依赖具体 Usage 实现类
        when(response.getMetadata().getUsage().getPromptTokens()).thenReturn(5);
        when(response.getMetadata().getUsage().getCompletionTokens()).thenReturn(7);
        when(response.getResult().getOutput().getText()).thenReturn("ok");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        SpringAiProviderAdapter adapter = new SpringAiProviderAdapter(
                config,
                chatModel,
                null,
                null
        );

        MediaContent image = new MediaContent(
                "id",
                "image/png",
                new byte[] {1, 2, 3},
                "a.png",
                3,
                java.util.Map.of()
        );

        LlmResponse llmResponse = adapter.callWithMedia("请看图", List.of(image), Duration.ofSeconds(1));

        assertEquals("ok", llmResponse.content());
        assertEquals(5, llmResponse.inputTokens());
        assertEquals(7, llmResponse.outputTokens());
        assertEquals("p1", llmResponse.providerId());
        assertEquals("gpt-vision", llmResponse.modelName());
    }
}

