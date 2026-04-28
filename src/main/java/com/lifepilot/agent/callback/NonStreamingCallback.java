package com.lifepilot.agent.callback;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.adapter.ProviderChatOptionsFactory;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.observability.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 非流式迭代回调 — run() 使用。
 *
 * <p>通过 {@code ChatModel.call(Prompt)} 直接调用 LLM，
 * 设置 {@code internalToolExecutionEnabled=false} 禁用自动 tool calling，
 * 返回原始 ChatResponse 供 coreLoop 解析 tool call 并手动执行。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class NonStreamingCallback implements IterationCallback {

    private static final Logger log = LoggerFactory.getLogger(NonStreamingCallback.class);

    private final AgentConfigProperties config;
    private final GenerationRouter generationRouter;
    @Nullable private final MultimodalRouter multimodalRouter;
    private final AgentRequest request;
    private final CallbackHelper helper;

    private String providerId = IterationCallback.DEFAULT_MODEL_ID;
    private String modelId = IterationCallback.DEFAULT_MODEL_ID;
    /**
     * 本次 LLM 调用累加的 reasoning_content 文本（推理模型多轮契约）。
     *
     * <p>同步路径下 reasoning 来自单次 ChatResponse 的 metadata；callback 在 turn 内
     * 复用单实例，每次 callLlm 入口重置避免跨轮污染（与 StreamingCallback 一致）。
     */
    private String finalReasoningContent = "";

    public NonStreamingCallback(AgentConfigProperties config,
                                GenerationRouter generationRouter,
                                @Nullable MultimodalRouter multimodalRouter,
                                AgentRequest request,
                                CallbackHelper helper) {
        this.config = config;
        this.generationRouter = generationRouter;
        this.multimodalRouter = multimodalRouter;
        this.request = request;
        this.helper = helper;
    }

    @Override
    public ChatResponse callLlm(AgentRequest req,
                                List<Message> messages,
                                List<ToolCallback> toolCallbacks,
                                @Nullable TraceContext traceContext) {
        String scene = config.getLoop().getLlmScene();

        // turn 内 callback 复用单实例（参见 StreamingCallback 同名设计）；每次 callLlm 入口
        // 重置 reasoning 累加，避免跨轮污染导致 ReactStep.ToolCall.reasoningContent 含历史残留
        this.finalReasoningContent = "";

        // 动态路由：仅当 UserMessage 含真正的多模态媒体（image / audio / video）时走多模态路径。
        // 文档类附件（pdf / docx / md / txt / csv 等）通过 file.read(attachmentId=...) 按需解析，不走多模态通道。
        boolean messagesHaveMultimodalMedia = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .flatMap(um -> um.getMedia().stream())
                .anyMatch(NonStreamingCallback::isMultimodalMedia);

        // 多模态路由：messages 中有真多模态媒体且 MultimodalRouter 可用时走多模态路径
        if (messagesHaveMultimodalMedia && multimodalRouter != null) {
            var mediaContents = req.mediaContents() != null && !req.mediaContents().isEmpty()
                    ? req.mediaContents()
                    : helper.extractMediaContentsFromMessages(messages);
            var multimodalRequest = new MultimodalRequest(
                    scene,
                    helper.buildConversationContextText(messages),
                    mediaContents,
                    null,
                    req.preferredProvider(),
                    null,
                    toolCallbacks
            );
            LlmResponse llmResponse = multimodalRouter.call(multimodalRequest);
            this.providerId = llmResponse.providerId();
            this.modelId = llmResponse.modelName();
            log.debug("非流式多模态路由完成: scene={}, provider={}, model={}",
                    scene, this.providerId, this.modelId);
            return helper.adaptToChatResponse(llmResponse);
        }

        if (messagesHaveMultimodalMedia) {
            log.warn("消息包含媒体内容但 MultimodalRouter 不可用，回退到纯文本路由");
        }

        // 纯文本路由：通过 GenerationRouter 获取 ChatModel
        var chatModelInfo = generationRouter.getChatModelWithInfo(
                scene, request.preferredProvider(), null);
        this.providerId = chatModelInfo.serviceId();
        this.modelId = chatModelInfo.modelName();

        var chatOptions = ProviderChatOptionsFactory.create(
                new ProviderChatOptionsFactory.ProviderDescriptor(
                        chatModelInfo.baseAdapter(),
                        chatModelInfo.apiUrl()
                ),
                chatModelInfo.chatModel(),
                chatModelInfo.modelName(),
                req.temperature(),
                toolCallbacks,
                false,
                false,
                null
        );

        // 构建 Prompt 并调用 ChatModel
        var prompt = new Prompt(messages, chatOptions);
        ChatResponse response = chatModelInfo.chatModel().call(prompt);
        // 同步路径补齐 reasoning_content：Spring AI 1.1.3+ OpenAiChatModel 把协议
        // reasoning_content 字段以 metadata key="reasoningContent" 塞入 AssistantMessage；
        // 与流式 StreamingCallback 行为对称（StreamingCallback.callLlmNonStreaming 同样实现）
        var result = response.getResult();
        if (result != null && result.getOutput() != null) {
            var metadata = result.getOutput().getMetadata();
            if (metadata != null) {
                Object rc = metadata.get("reasoningContent");
                if (rc instanceof String reasoning) {
                    // 不跳过空串 —— DeepSeek thinking 模式短响应会返回空 reasoning，
                    // 多轮契约仍要求字段存在；空串走完整 marker 链路注入空字段满足契约。
                    this.finalReasoningContent = reasoning;
                }
            }
        }
        return response;
    }

    @Override public String getProviderId() { return providerId; }
    @Override public String getModelId() { return modelId; }

    @Override public String getFinalReasoningContent() { return finalReasoningContent; }

    /** 判断 Media 是否属于真正的多模态类型（image / audio / video）。 */
    private static boolean isMultimodalMedia(org.springframework.ai.content.Media media) {
        var mime = media.getMimeType();
        if (mime == null) {
            return false;
        }
        String type = mime.getType();
        return "image".equalsIgnoreCase(type)
                || "audio".equalsIgnoreCase(type)
                || "video".equalsIgnoreCase(type);
    }
}
