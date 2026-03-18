package com.lifepilot.agent.callback;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRequest;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.observability.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;

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
    private final LlmRouter llmRouter;
    @Nullable private final MultimodalRouter multimodalRouter;
    private final AgentRequest request;
    private final CallbackHelper helper;

    private String providerId = IterationCallback.DEFAULT_MODEL_ID;
    private String modelId = IterationCallback.DEFAULT_MODEL_ID;

    public NonStreamingCallback(AgentConfigProperties config,
                                LlmRouter llmRouter,
                                @Nullable MultimodalRouter multimodalRouter,
                                AgentRequest request,
                                CallbackHelper helper) {
        this.config = config;
        this.llmRouter = llmRouter;
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

        // 动态路由：检查 messages 中 UserMessage 是否包含 Media 对象
        // 覆盖两种场景：用户上传的媒体（首轮）和工具产生的媒体（后续迭代 pendingMedia）
        boolean messagesHaveMedia = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .anyMatch(um -> !um.getMedia().isEmpty());

        // 多模态路由：messages 中有 Media 且 MultimodalRouter 可用时走多模态路径
        if (messagesHaveMedia && multimodalRouter != null) {
            var mediaContents = req.mediaContents() != null && !req.mediaContents().isEmpty()
                    ? req.mediaContents()
                    : helper.extractMediaContentsFromMessages(messages);
            var multimodalRequest = new MultimodalRequest(
                    scene,
                    helper.buildConversationContextText(messages),
                    mediaContents,
                    null,
                    req.preferredProvider(),
                    null
            );
            LlmResponse llmResponse = multimodalRouter.call(multimodalRequest);
            this.providerId = llmResponse.providerId();
            this.modelId = llmResponse.modelName();
            log.debug("非流式多模态路由完成: scene={}, provider={}, model={}",
                    scene, this.providerId, this.modelId);
            return helper.adaptToChatResponse(llmResponse);
        }

        if (messagesHaveMedia) {
            log.warn("消息包含媒体内容但 MultimodalRouter 不可用，回退到纯文本路由");
        }

        // 纯文本路由：原有 LlmRouter 路径
        var chatModelInfo = llmRouter.getChatModelWithInfo(scene, request.preferredProvider());
        this.providerId = chatModelInfo.providerId();
        this.modelId = chatModelInfo.modelId();

        // 构建 ChatOptions：注入工具定义但禁用自动执行
        var optionsBuilder = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false);

        // 温度透传
        if (req.temperature() != null) {
            optionsBuilder.temperature(req.temperature());
        }

        if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
            var validCallbacks = toolCallbacks.stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (!validCallbacks.isEmpty()) {
                optionsBuilder.toolCallbacks(validCallbacks);
            }
        }

        // 构建 Prompt 并调用 ChatModel
        var prompt = new Prompt(messages, optionsBuilder.build());
        return chatModelInfo.chatModel().call(prompt);
    }

    @Override public String getProviderId() { return providerId; }
    @Override public String getModelId() { return modelId; }
}
