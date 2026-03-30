package com.lifepilot.agent.callback;

import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.config.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StreamingCallback 单元测试。
 *
 * @author zsg
 * @since 2026-03-30
 */
@ExtendWith(MockitoExtension.class)
class StreamingCallback_单元测试 {

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private CallbackHelper helper;

    @Mock
    private SseSessionManager sseManager;

    @Mock
    private ChatModel chatModel;

    private AgentConfigProperties config;
    private AgentRequest request;
    private AgentLoopContext loopContext;

    @BeforeEach
    void setUp() {
        config = new AgentConfigProperties();
        request = new AgentRequest("请继续回答", "session-1", "web");
        loopContext = new AgentLoopContext(null, "stream-1", "turn-1");

        when(generationRouter.getChatModelWithInfo(anyString(), any(), isNull()))
                .thenReturn(new GenerationRouter.ChatModelInfo(
                        chatModel,
                        "provider-1",
                        "model-1",
                        ProviderType.OPENAI_COMPATIBLE,
                        "https://example.test/v1",
                        true
                ));
        when(sseManager.getEmitter("stream-1")).thenReturn(new SseEmitter());
        when(helper.enhanceSystemPromptForStreaming(anyString(), any()))
                .thenAnswer(invocation -> {
                    String systemText = invocation.getArgument(0, String.class);
                    String a2uiPrompt = invocation.getArgument(1, String.class);
                    return a2uiPrompt == null || a2uiPrompt.isBlank()
                            ? systemText
                            : systemText + "\n" + a2uiPrompt;
                });
        when(helper.getA2uiMaxComponents()).thenReturn(20);
        doNothing().when(helper).logLlmPromptIfEnabled(anyString(), anyList(), any());
        doNothing().when(helper).recordStreamingLlmStep(any(), any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void 纯文本流式分片应合批为更少的Token事件() {
        when(helper.isA2uiEnabled()).thenReturn(false);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                textChunk("你好"),
                textChunk("世界")
        ));

        StreamingCallback callback = new StreamingCallback(
                config,
                generationRouter,
                null,
                helper,
                new CancellationToken(),
                loopContext,
                sseManager,
                "stream-1",
                "session-1",
                "turn-1",
                request
        );

        callback.callLlm(request, basicMessages(), List.of(), null);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseManager, times(1)).sendEvent(eq("stream-1"), eq(SseEventType.TOKEN), payloadCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsEntry("content", "你好世界");
        assertThat(loopContext.streamingTimingsMs())
                .containsKeys("modelStreamStartToFirstTokenMs", "requestReceivedToFirstTokenSseMs");
    }

    @Test
    void A2ui标签跨Chunk时应保留普通文本并只发送一次UI事件() {
        when(helper.isA2uiEnabled()).thenReturn(true);
        when(helper.parseAndValidateA2uiTree(anyString(), eq(20))).thenReturn(sampleTree());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                textChunk("普通文本<a2"),
                textChunk("ui>{\"components\":[{\"id\":\"card-1\",\"type\":\"Card\",\"properties\":{\"title\":\"面板\"},\"children\":[]}]}</a2ui>")
        ));

        StreamingCallback callback = new StreamingCallback(
                config,
                generationRouter,
                null,
                helper,
                new CancellationToken(),
                loopContext,
                sseManager,
                "stream-1",
                "session-1",
                "turn-1",
                request
        );

        callback.callLlm(request, basicMessages(), List.of(), null);

        ArgumentCaptor<Object> tokenCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseManager, times(1)).sendEvent(eq("stream-1"), eq(SseEventType.TOKEN), tokenCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> tokenPayload = (Map<String, Object>) tokenCaptor.getValue();
        assertThat(tokenPayload).containsEntry("content", "普通文本");

        ArgumentCaptor<Object> uiCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseManager, times(1)).sendEvent(eq("stream-1"), eq(SseEventType.UI), uiCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> uiPayload = (Map<String, Object>) uiCaptor.getValue();
        assertThat(uiPayload).containsKey("components");
        assertThat(loopContext.getLastCollectedA2uiTree()).isEqualTo(sampleTree());
    }

    @Test
    void 流式检测到ToolCall时应先发出准备调用工具事件() {
        when(helper.isA2uiEnabled()).thenReturn(false);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                toolCallChunk("tool.search", "{\"q\":\"知微\"}")
        ));

        StreamingCallback callback = new StreamingCallback(
                config,
                generationRouter,
                null,
                helper,
                new CancellationToken(),
                loopContext,
                sseManager,
                "stream-1",
                "session-1",
                "turn-1",
                request
        );

        callback.callLlm(request, basicMessages(), List.of(), null);

        verify(helper, times(1)).sendReasoningEvent(
                eq(sseManager),
                eq("stream-1"),
                eq("session-1"),
                eq("turn-1"),
                eq("PROGRESS"),
                eq("准备调用工具"),
                contains("tool.search"),
                eq("tool.search"),
                anyMap()
        );
    }

    private List<Message> basicMessages() {
        return List.of(
                new SystemMessage("你是一个严谨的助手。"),
                new UserMessage("请继续回答")
        );
    }

    private ChatResponse textChunk(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().build()
        );
    }

    private ChatResponse toolCallChunk(String toolName, String arguments) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, arguments)))
                .build();
        return new ChatResponse(
                List.of(new Generation(assistantMessage)),
                ChatResponseMetadata.builder().build()
        );
    }

    private A2uiComponentTree sampleTree() {
        return new A2uiComponentTree(List.of(
                new A2uiComponent("card-1", "Card", Map.of("title", "面板"), List.of(), null)
        ));
    }
}
