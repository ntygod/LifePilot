package com.lifepilot.agent.callback;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NonStreamingCallback 单元测试。
 *
 * <p>覆盖纯文本路由、多模态路由、多模态回退、Provider/Model 追踪、
 * 异常传播等核心行为。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class NonStreamingCallback_单元测试 {

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private MultimodalRouter multimodalRouter;

    @Mock
    private CallbackHelper helper;

    @Mock
    private ChatModel chatModel;

    private AgentConfigProperties config;
    private AgentRequest request;

    @BeforeEach
    void 初始化() {
        config = new AgentConfigProperties();
        request = new AgentRequest("你好", "session-1", "web");
    }

    // ==================== 辅助方法 ====================

    /** 构建被测对象（包含 MultimodalRouter）。 */
    private NonStreamingCallback 创建回调() {
        return new NonStreamingCallback(config, generationRouter, multimodalRouter, request, helper);
    }

    /** 构建被测对象（不包含 MultimodalRouter）。 */
    private NonStreamingCallback 创建无多模态路由回调() {
        return new NonStreamingCallback(config, generationRouter, null, request, helper);
    }

    /** 纯文本消息列表。 */
    private List<Message> 纯文本消息() {
        return List.of(
                new SystemMessage("你是一个助手。"),
                new UserMessage("你好")
        );
    }

    /** 含媒体的消息列表（UserMessage 包含一张图片 Media）。 */
    private List<Message> 含媒体消息() {
        UserMessage mediaMessage = UserMessage.builder()
                .text("描述这张图片")
                .media(new org.springframework.ai.content.Media(
                        MimeTypeUtils.IMAGE_PNG,
                        new ByteArrayResource(new byte[]{1, 2, 3})
                ))
                .build();
        return List.of(
                new SystemMessage("你是一个视觉助手。"),
                mediaMessage
        );
    }

    /** 标准 ChatModelInfo 返回值。 */
    private GenerationRouter.ChatModelInfo 标准ChatModelInfo() {
        return new GenerationRouter.ChatModelInfo(
                chatModel,
                "provider-a",
                "model-a",
                ProviderType.OPENAI_COMPATIBLE,
                "https://api.example.com/v1",
                false
        );
    }

    /** 构建简单的 ChatResponse。 */
    private ChatResponse 文本响应(String text) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().build()
        );
    }

    /** 构建包含 ToolCall 的 ChatResponse。 */
    private ChatResponse 工具调用响应(String toolName, String arguments) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, arguments)))
                .build();
        return new ChatResponse(
                List.of(new Generation(assistantMessage)),
                ChatResponseMetadata.builder().build()
        );
    }

    // ==================== 纯文本路由 ====================

    @Nested
    class 纯文本路由 {

        @BeforeEach
        void 配置纯文本路由() {
            when(generationRouter.getChatModelWithInfo(anyString(), any(), isNull()))
                    .thenReturn(标准ChatModelInfo());
        }

        @Test
        void 正常调用_应通过GenerationRouter获取ChatModel并返回响应() {
            ChatResponse expected = 文本响应("你好！我是知微助手。");
            when(chatModel.call(any(Prompt.class))).thenReturn(expected);

            NonStreamingCallback callback = 创建回调();
            ChatResponse actual = callback.callLlm(request, 纯文本消息(), List.of(), null);

            assertThat(actual).isSameAs(expected);
            verify(generationRouter).getChatModelWithInfo("agent_react", null, null);
            verify(chatModel).call(any(Prompt.class));
        }

        @Test
        void 正常调用_应正确更新ProviderId和ModelId() {
            when(chatModel.call(any(Prompt.class))).thenReturn(文本响应("ok"));

            NonStreamingCallback callback = 创建回调();
            callback.callLlm(request, 纯文本消息(), List.of(), null);

            assertThat(callback.getProviderId()).isEqualTo("provider-a");
            assertThat(callback.getModelId()).isEqualTo("model-a");
        }

        @Test
        void 携带工具回调列表_应传递到Prompt构建() {
            when(chatModel.call(any(Prompt.class))).thenReturn(文本响应("已查到结果"));

            ToolCallback mockTool = mock(ToolCallback.class);
            List<ToolCallback> toolCallbacks = List.of(mockTool);

            NonStreamingCallback callback = 创建回调();
            callback.callLlm(request, 纯文本消息(), toolCallbacks, null);

            ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(promptCaptor.capture());
            // Prompt 应包含原始消息
            assertThat(promptCaptor.getValue().getInstructions()).hasSize(2);
        }

        @Test
        void 返回包含ToolCall的响应_应原样传递不执行() {
            ChatResponse toolCallResponse = 工具调用响应("tool.search", "{\"q\":\"知微\"}");
            when(chatModel.call(any(Prompt.class))).thenReturn(toolCallResponse);

            NonStreamingCallback callback = 创建回调();
            ChatResponse actual = callback.callLlm(request, 纯文本消息(), List.of(), null);

            // 回调不应自行处理 tool call，应原样返回
            assertThat(actual).isSameAs(toolCallResponse);
            assertThat(actual.getResult().getOutput().getToolCalls()).hasSize(1);
            assertThat(actual.getResult().getOutput().getToolCalls().getFirst().name())
                    .isEqualTo("tool.search");
        }

        @Test
        void 使用偏好Provider的请求_应传递preferredProvider到路由() {
            AgentRequest reqWithProvider = new AgentRequest(
                    "你好", "session-1", "web", null, null, null,
                    null, null, null, 0, "my-provider", null, null, null, null
            );
            NonStreamingCallback callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, reqWithProvider, helper);

            when(chatModel.call(any(Prompt.class))).thenReturn(文本响应("ok"));

            callback.callLlm(reqWithProvider, 纯文本消息(), List.of(), null);

            // request.preferredProvider() 传递到 getChatModelWithInfo
            verify(generationRouter).getChatModelWithInfo("agent_react", "my-provider", null);
        }

        @Test
        void 自定义LlmScene_应使用配置中的场景标识() {
            config.getLoop().setLlmScene("custom_scene");
            when(chatModel.call(any(Prompt.class))).thenReturn(文本响应("ok"));

            NonStreamingCallback callback = 创建回调();
            callback.callLlm(request, 纯文本消息(), List.of(), null);

            verify(generationRouter).getChatModelWithInfo("custom_scene", null, null);
        }

        @Test
        void 请求含温度参数_应传入构建ChatOptions() {
            AgentRequest reqWithTemp = new AgentRequest(
                    "你好", "session-1", "web", null, null, null,
                    null, null, null, 0, null, null, null, 0.5, null
            );
            NonStreamingCallback callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, reqWithTemp, helper);

            when(chatModel.call(any(Prompt.class))).thenReturn(文本响应("ok"));

            callback.callLlm(reqWithTemp, 纯文本消息(), List.of(), null);

            // 不抛异常，说明 temperature 参数被正确传递到 ProviderChatOptionsFactory.create()
            verify(chatModel).call(any(Prompt.class));
        }
    }

    // ==================== 多模态路由 ====================

    @Nested
    class 多模态路由 {

        @Test
        void 消息含媒体且MultimodalRouter可用_应走多模态路径() {
            LlmResponse llmResponse = new LlmResponse(
                    "这是一张猫的图片", 100, 50, "vision-provider", "vision-model", 500, false);
            ChatResponse adaptedResponse = 文本响应("这是一张猫的图片");

            when(multimodalRouter.call(any(MultimodalRequest.class))).thenReturn(llmResponse);
            when(helper.adaptToChatResponse(llmResponse)).thenReturn(adaptedResponse);
            when(helper.buildConversationContextText(anyList())).thenReturn("对话上下文文本");

            // request 无 mediaContents → 触发从消息中提取
            List<MediaContent> extracted = List.of(
                    new MediaContent("img-1", "image/png", new byte[]{1, 2}, "test.png", 2, Map.of())
            );
            when(helper.extractMediaContentsFromMessages(anyList())).thenReturn(extracted);

            NonStreamingCallback callback = 创建回调();
            ChatResponse actual = callback.callLlm(request, 含媒体消息(), List.of(), null);

            assertThat(actual).isSameAs(adaptedResponse);
            assertThat(callback.getProviderId()).isEqualTo("vision-provider");
            assertThat(callback.getModelId()).isEqualTo("vision-model");

            // 不应调用 GenerationRouter
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 多模态请求_应正确构建MultimodalRequest() {
            LlmResponse llmResponse = new LlmResponse(
                    "响应", 100, 50, "provider-v", "model-v", 300, false);
            when(multimodalRouter.call(any(MultimodalRequest.class))).thenReturn(llmResponse);
            when(helper.adaptToChatResponse(llmResponse)).thenReturn(文本响应("响应"));
            when(helper.buildConversationContextText(anyList())).thenReturn("上下文文本");

            List<MediaContent> extracted = List.of(
                    new MediaContent("img-2", "image/jpeg", new byte[]{4, 5}, "photo.jpg", 2, Map.of())
            );
            when(helper.extractMediaContentsFromMessages(anyList())).thenReturn(extracted);

            NonStreamingCallback callback = 创建回调();
            callback.callLlm(request, 含媒体消息(), List.of(), null);

            ArgumentCaptor<MultimodalRequest> captor = ArgumentCaptor.forClass(MultimodalRequest.class);
            verify(multimodalRouter).call(captor.capture());

            MultimodalRequest captured = captor.getValue();
            assertThat(captured.scene()).isEqualTo("agent_react");
            assertThat(captured.text()).isEqualTo("上下文文本");
            assertThat(captured.mediaList()).isEqualTo(extracted);
            assertThat(captured.preferredProviderId()).isNull(); // request.preferredProvider() 为 null
        }

        @Test
        void 请求自带mediaContents_应优先使用而非从消息提取() {
            List<MediaContent> reqMedia = List.of(
                    new MediaContent("req-img-1", "image/png", new byte[]{9, 8}, "req.png", 2, Map.of())
            );
            AgentRequest reqWithMedia = new AgentRequest(
                    "描述图片", "session-1", "web", null, null, null,
                    null, null, null, 0, null, null, reqMedia, null, null
            );

            LlmResponse llmResponse = new LlmResponse(
                    "响应", 100, 50, "p1", "m1", 200, false);
            when(multimodalRouter.call(any(MultimodalRequest.class))).thenReturn(llmResponse);
            when(helper.adaptToChatResponse(llmResponse)).thenReturn(文本响应("响应"));
            when(helper.buildConversationContextText(anyList())).thenReturn("文本");

            NonStreamingCallback callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, reqWithMedia, helper);
            callback.callLlm(reqWithMedia, 含媒体消息(), List.of(), null);

            ArgumentCaptor<MultimodalRequest> captor = ArgumentCaptor.forClass(MultimodalRequest.class);
            verify(multimodalRouter).call(captor.capture());
            assertThat(captor.getValue().mediaList()).isEqualTo(reqMedia);

            // 不应从消息中提取
            verify(helper, never()).extractMediaContentsFromMessages(anyList());
        }

        @Test
        void 请求mediaContents为空列表_应从消息中提取() {
            AgentRequest reqWithEmptyMedia = new AgentRequest(
                    "描述图片", "session-1", "web", null, null, null,
                    null, null, null, 0, null, null, List.of(), null, null
            );

            List<MediaContent> extracted = List.of(
                    new MediaContent("msg-img", "image/png", new byte[]{1}, "pic.png", 1, Map.of())
            );

            LlmResponse llmResponse = new LlmResponse(
                    "响应", 10, 5, "p1", "m1", 100, false);
            when(multimodalRouter.call(any(MultimodalRequest.class))).thenReturn(llmResponse);
            when(helper.adaptToChatResponse(llmResponse)).thenReturn(文本响应("响应"));
            when(helper.buildConversationContextText(anyList())).thenReturn("文本");
            when(helper.extractMediaContentsFromMessages(anyList())).thenReturn(extracted);

            NonStreamingCallback callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, reqWithEmptyMedia, helper);
            callback.callLlm(reqWithEmptyMedia, 含媒体消息(), List.of(), null);

            ArgumentCaptor<MultimodalRequest> captor = ArgumentCaptor.forClass(MultimodalRequest.class);
            verify(multimodalRouter).call(captor.capture());
            assertThat(captor.getValue().mediaList()).isEqualTo(extracted);
        }

        @Test
        void 多模态请求带偏好Provider_应传递到MultimodalRequest() {
            AgentRequest reqWithPref = new AgentRequest(
                    "描述图片", "session-1", "web", null, null, null,
                    null, null, null, 0, "preferred-vision", null, null, null, null
            );

            LlmResponse llmResponse = new LlmResponse(
                    "响应", 10, 5, "preferred-vision", "vm1", 100, false);
            when(multimodalRouter.call(any(MultimodalRequest.class))).thenReturn(llmResponse);
            when(helper.adaptToChatResponse(llmResponse)).thenReturn(文本响应("响应"));
            when(helper.buildConversationContextText(anyList())).thenReturn("文本");

            List<MediaContent> extracted = List.of(
                    new MediaContent("img-x", "image/png", new byte[]{1}, "x.png", 1, Map.of())
            );
            when(helper.extractMediaContentsFromMessages(anyList())).thenReturn(extracted);

            NonStreamingCallback callback = new NonStreamingCallback(
                    config, generationRouter, multimodalRouter, reqWithPref, helper);
            callback.callLlm(reqWithPref, 含媒体消息(), List.of(), null);

            ArgumentCaptor<MultimodalRequest> captor = ArgumentCaptor.forClass(MultimodalRequest.class);
            verify(multimodalRouter).call(captor.capture());
            assertThat(captor.getValue().preferredProviderId()).isEqualTo("preferred-vision");
        }
    }

    // ==================== 多模态回退到纯文本 ====================

    @Nested
    class 多模态回退 {

        @Test
        void 消息含媒体但MultimodalRouter为null_应回退到纯文本路由() {
            when(generationRouter.getChatModelWithInfo(anyString(), any(), isNull()))
                    .thenReturn(标准ChatModelInfo());
            ChatResponse expected = 文本响应("无法处理图片，仅文本回复");
            when(chatModel.call(any(Prompt.class))).thenReturn(expected);

            NonStreamingCallback callback = 创建无多模态路由回调();
            ChatResponse actual = callback.callLlm(request, 含媒体消息(), List.of(), null);

            assertThat(actual).isSameAs(expected);
            // 应走 GenerationRouter 纯文本路径
            verify(generationRouter).getChatModelWithInfo("agent_react", null, null);
            verify(chatModel).call(any(Prompt.class));
        }

        @Test
        void 纯文本消息即使MultimodalRouter可用_也应走纯文本路径() {
            when(generationRouter.getChatModelWithInfo(anyString(), any(), isNull()))
                    .thenReturn(标准ChatModelInfo());
            when(chatModel.call(any(Prompt.class))).thenReturn(文本响应("纯文本回复"));

            NonStreamingCallback callback = 创建回调();
            callback.callLlm(request, 纯文本消息(), List.of(), null);

            // 不应调用 MultimodalRouter
            verifyNoInteractions(multimodalRouter);
            verify(generationRouter).getChatModelWithInfo(anyString(), any(), isNull());
        }
    }

    // ==================== 异常处理 ====================

    @Nested
    class 异常处理 {

        @Test
        void ChatModel调用抛异常_应传播而非吞掉() {
            when(generationRouter.getChatModelWithInfo(anyString(), any(), isNull()))
                    .thenReturn(标准ChatModelInfo());
            when(chatModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("LLM 服务超时"));

            NonStreamingCallback callback = 创建回调();

            assertThatThrownBy(() -> callback.callLlm(request, 纯文本消息(), List.of(), null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("LLM 服务超时");
        }

        @Test
        void GenerationRouter无可用服务_应抛出LlmUnavailableException() {
            when(generationRouter.getChatModelWithInfo(anyString(), any(), isNull()))
                    .thenThrow(new LlmUnavailableException("无可用 ChatModel: scene=agent_react",
                            "agent_react", List.of()));

            NonStreamingCallback callback = 创建回调();

            assertThatThrownBy(() -> callback.callLlm(request, 纯文本消息(), List.of(), null))
                    .isInstanceOf(LlmUnavailableException.class)
                    .hasMessageContaining("无可用 ChatModel");
        }

        @Test
        void MultimodalRouter调用失败_应传播异常() {
            when(multimodalRouter.call(any(MultimodalRequest.class)))
                    .thenThrow(new LlmUnavailableException("VISION Provider 不可用",
                            "agent_react", List.of("provider-v1")));
            when(helper.buildConversationContextText(anyList())).thenReturn("文本");

            List<MediaContent> extracted = List.of(
                    new MediaContent("img-err", "image/png", new byte[]{1}, "err.png", 1, Map.of())
            );
            when(helper.extractMediaContentsFromMessages(anyList())).thenReturn(extracted);

            NonStreamingCallback callback = 创建回调();

            assertThatThrownBy(() -> callback.callLlm(request, 含媒体消息(), List.of(), null))
                    .isInstanceOf(LlmUnavailableException.class)
                    .hasMessageContaining("VISION Provider 不可用");
        }
    }

    // ==================== 默认状态与接口契约 ====================

    @Test
    void 调用前默认ProviderId和ModelId为默认值() {
        NonStreamingCallback callback = 创建回调();

        assertThat(callback.getProviderId()).isEqualTo(IterationCallback.DEFAULT_MODEL_ID);
        assertThat(callback.getModelId()).isEqualTo(IterationCallback.DEFAULT_MODEL_ID);
    }

    @Test
    void recordsLlmStep默认返回false() {
        NonStreamingCallback callback = 创建回调();
        assertThat(callback.recordsLlmStep()).isFalse();
    }
}
