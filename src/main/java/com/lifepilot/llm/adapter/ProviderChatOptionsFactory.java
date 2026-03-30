package com.lifepilot.llm.adapter;

import com.lifepilot.llm.config.ProviderType;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * Provider ChatOptions 构造工厂。
 *
 * <p>根据底层 Provider 类型与 API Host 生成对应厂商专有参数，
 * 确保结构化输出、流式 usage 和 tool calling 配置能够真正下发到协议层。</p>
 *
 * @author zsg
 * @since 2026-03-30
 */
public final class ProviderChatOptionsFactory {

    private static final String STRUCTURED_OUTPUT_SCHEMA_NAME = "zhiwei_structured_output";
    private static final String OPENAI_HOST = "api.openai.com";
    private static final String QWEN_HOST = "dashscope.aliyuncs.com";
    private static final String DEEPSEEK_HOST = "api.deepseek.com";

    private ProviderChatOptionsFactory() {
    }

    public record ProviderDescriptor(
            ProviderType type,
            String apiUrl
    ) {
        public ProviderDescriptor {
            Objects.requireNonNull(type, "Provider 类型不能为空");
            Objects.requireNonNull(apiUrl, "apiUrl 不能为空");
        }
    }

    public static boolean supportsProtocolStructuredOutput(ProviderDescriptor provider) {
        Objects.requireNonNull(provider, "provider 不能为空");
        if (provider.type() == ProviderType.ANTHROPIC) {
            return true;
        }
        return resolveOpenAiStructuredOutputMode(provider) == OpenAiStructuredOutputMode.JSON_SCHEMA;
    }

    public static ChatOptions create(ProviderDescriptor provider,
                                     ChatModel chatModel,
                                     String modelName,
                                     @Nullable Double temperature,
                                     @Nullable List<ToolCallback> toolCallbacks,
                                     boolean internalToolExecutionEnabled,
                                     boolean streamUsage,
                                     @Nullable String outputSchema) {
        Objects.requireNonNull(provider, "provider 不能为空");
        Objects.requireNonNull(chatModel, "chatModel 不能为空");
        List<ToolCallback> validToolCallbacks = toolCallbacks == null
                ? List.of()
                : toolCallbacks.stream().filter(Objects::nonNull).toList();

        if (chatModel instanceof OpenAiChatModel) {
            return createOpenAiOptions(
                    provider,
                    modelName,
                    temperature,
                    validToolCallbacks,
                    internalToolExecutionEnabled,
                    streamUsage,
                    outputSchema
            );
        }
        if (chatModel instanceof AnthropicChatModel) {
            return createAnthropicOptions(
                    modelName,
                    temperature,
                    validToolCallbacks,
                    internalToolExecutionEnabled,
                    outputSchema
            );
        }
        return createGenericOptions(temperature, validToolCallbacks, internalToolExecutionEnabled);
    }

    private static ChatOptions createOpenAiOptions(ProviderDescriptor provider,
                                                   String modelName,
                                                   @Nullable Double temperature,
                                                   List<ToolCallback> toolCallbacks,
                                                   boolean internalToolExecutionEnabled,
                                                   boolean streamUsage,
                                                   @Nullable String outputSchema) {
        var builder = OpenAiChatOptions.builder()
                .model(modelName)
                .internalToolExecutionEnabled(internalToolExecutionEnabled);

        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks);
        }
        if (streamUsage) {
            builder.streamUsage(true);
        }
        if (hasText(outputSchema)) {
            String schema = outputSchema.trim();
            switch (resolveOpenAiStructuredOutputMode(provider)) {
                case JSON_SCHEMA -> {
                    builder.outputSchema(schema);
                    builder.responseFormat(ResponseFormat.builder()
                            .type(ResponseFormat.Type.JSON_SCHEMA)
                            .jsonSchema(ResponseFormat.JsonSchema.builder()
                                    .name(STRUCTURED_OUTPUT_SCHEMA_NAME)
                                    .schema(schema)
                                    .strict(Boolean.TRUE)
                                    .build())
                            .build());
                }
                case JSON_OBJECT -> builder.responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build());
                case NONE -> {
                    // 未知兼容层不下发厂商特有结构化参数，交由上层回退到提示词约束。
                }
            }
        }
        return builder.build();
    }

    private static ChatOptions createAnthropicOptions(String modelName,
                                                      @Nullable Double temperature,
                                                      List<ToolCallback> toolCallbacks,
                                                      boolean internalToolExecutionEnabled,
                                                      @Nullable String outputSchema) {
        var builder = AnthropicChatOptions.builder()
                .model(modelName)
                .internalToolExecutionEnabled(internalToolExecutionEnabled);

        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks);
        }
        if (hasText(outputSchema)) {
            builder.outputSchema(outputSchema.trim());
        }
        return builder.build();
    }

    private static ChatOptions createGenericOptions(@Nullable Double temperature,
                                                    List<ToolCallback> toolCallbacks,
                                                    boolean internalToolExecutionEnabled) {
        var builder = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(internalToolExecutionEnabled);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks);
        }
        return builder.build();
    }

    private static OpenAiStructuredOutputMode resolveOpenAiStructuredOutputMode(ProviderDescriptor provider) {
        if (provider.type() != ProviderType.OPENAI_COMPATIBLE) {
            return OpenAiStructuredOutputMode.NONE;
        }
        String host = resolveHost(provider.apiUrl());
        if (OPENAI_HOST.equalsIgnoreCase(host) || QWEN_HOST.equalsIgnoreCase(host)) {
            return OpenAiStructuredOutputMode.JSON_SCHEMA;
        }
        if (DEEPSEEK_HOST.equalsIgnoreCase(host)) {
            return OpenAiStructuredOutputMode.JSON_OBJECT;
        }
        return OpenAiStructuredOutputMode.NONE;
    }

    @Nullable
    private static String resolveHost(String apiUrl) {
        if (!hasText(apiUrl)) {
            return null;
        }
        try {
            return new URI(apiUrl.trim()).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private enum OpenAiStructuredOutputMode {
        JSON_SCHEMA,
        JSON_OBJECT,
        NONE
    }
}
