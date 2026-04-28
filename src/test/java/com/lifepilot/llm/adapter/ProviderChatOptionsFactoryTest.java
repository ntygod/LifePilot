package com.lifepilot.llm.adapter;

import com.lifepilot.llm.profile.BaseAdapterType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ProviderChatOptionsFactory 单元测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
class ProviderChatOptionsFactoryTest {

    private static final ProviderChatOptionsFactory.ProviderDescriptor OPENAI_PROVIDER =
            new ProviderChatOptionsFactory.ProviderDescriptor(
                    BaseAdapterType.OPENAI_BASE,
                    "https://api.openai.com/v1"
            );

    private static final ProviderChatOptionsFactory.ProviderDescriptor ANTHROPIC_PROVIDER =
            new ProviderChatOptionsFactory.ProviderDescriptor(
                    BaseAdapterType.ANTHROPIC_BASE,
                    "https://api.anthropic.com"
            );

    @Test
    void openAi模型生成协议级结构化输出与流式Usage选项() {
        String schema = """
                {"type":"object","properties":{"answer":{"type":"string"}}}
                """;

        OpenAiChatOptions options = assertInstanceOf(
                OpenAiChatOptions.class,
                ProviderChatOptionsFactory.create(
                        OPENAI_PROVIDER,
                        mock(OpenAiChatModel.class),
                        "gpt-4.1",
                        0.2,
                        null,
                        false,
                        true,
                        schema
                )
        );

        assertEquals("gpt-4.1", options.getModel());
        assertEquals(Boolean.TRUE, options.getStreamUsage());
        assertEquals(ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
        assertEquals("zhiwei_structured_output", options.getResponseFormat().getJsonSchema().getName());
        assertEquals(Boolean.TRUE, options.getResponseFormat().getJsonSchema().getStrict());
        assertEquals("object", options.getResponseFormat().getJsonSchema().getSchema().get("type"));
        assertTrue(ProviderChatOptionsFactory.supportsProtocolStructuredOutput(OPENAI_PROVIDER));
    }

    @Test
    void anthropic模型生成原生结构化输出选项() {
        String schema = """
                {"type":"object","properties":{"summary":{"type":"string"}}}
                """;

        AnthropicChatOptions options = assertInstanceOf(
                AnthropicChatOptions.class,
                ProviderChatOptionsFactory.create(
                        ANTHROPIC_PROVIDER,
                        mock(AnthropicChatModel.class),
                        "claude-sonnet-4-5",
                        0.1,
                        null,
                        false,
                        false,
                        schema
                )
        );

        assertEquals("claude-sonnet-4-5", options.getModel());
        assertEquals(schema.trim(), options.getOutputSchema());
        assertTrue(ProviderChatOptionsFactory.supportsProtocolStructuredOutput(ANTHROPIC_PROVIDER));
    }

    @Test
    void deepSeek模型应使用jsonObject而不是jsonSchema() {
        String schema = """
                {"type":"object","properties":{"summary":{"type":"string"}}}
                """;
        var deepSeekProvider = new ProviderChatOptionsFactory.ProviderDescriptor(
                BaseAdapterType.OPENAI_BASE,
                "https://api.deepseek.com/v1"
        );

        OpenAiChatOptions options = assertInstanceOf(
                OpenAiChatOptions.class,
                ProviderChatOptionsFactory.create(
                        deepSeekProvider,
                        mock(OpenAiChatModel.class),
                        "deepseek-chat",
                        0.1,
                        null,
                        false,
                        false,
                        schema
                )
        );

        assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
        assertNull(options.getOutputSchema());
        assertFalse(ProviderChatOptionsFactory.supportsProtocolStructuredOutput(deepSeekProvider));
    }
}
