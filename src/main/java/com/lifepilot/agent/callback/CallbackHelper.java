package com.lifepilot.agent.callback;

import com.lifepilot.interaction.web.sse.SseEventBuffer;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.observability.trace.TraceContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 回调辅助接口 — 提供 NonStreamingCallback / StreamingCallback 所需的共享方法。
 *
 * <p>由 ReactAgentLoop 实现，解耦回调类对外部类的直接依赖。
 * 后续 Tasks 8-10 将这些方法迁移到独立处理器后，此接口可进一步精简。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public interface CallbackHelper {

    /** 从消息列表中提取 MediaContent。 */
    List<MediaContent> extractMediaContentsFromMessages(List<Message> messages);

    /** 将消息列表序列化为文本，供多模态路由使用。 */
    String buildConversationContextText(List<Message> messages);

    /** 将 LlmResponse 适配为 ChatResponse。 */
    ChatResponse adaptToChatResponse(LlmResponse llmResponse);

    /** 发送 REASONING SSE 事件。 */
    void sendReasoningEvent(SseSessionManager sseManager, String streamId,
                            String sessionId, String turnId,
                            String type, String title, String description,
                            @Nullable String toolName, Map<String, Object> extra,
                            @Nullable SseEventBuffer eventBuffer);

    /** 记录流式 LLM 调用步骤到 Trace。 */
    void recordStreamingLlmStep(@Nullable TraceContext traceContext,
                                Instant startTime, String providerId,
                                String modelId, String scene,
                                ChatResponse chatResponse,
                                @Nullable Exception error);

    /** 增强系统提示词（注入流式约束）。 */
    String enhanceSystemPromptForStreaming(String systemText);

    /** 调试日志 — 打印发送给 LLM 的完整消息列表。 */
    void logLlmPromptIfEnabled(String scene, List<Message> messages,
                               @Nullable List<ToolCallback> toolCallbacks);
}
