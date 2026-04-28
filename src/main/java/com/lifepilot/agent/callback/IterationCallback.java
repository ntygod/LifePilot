package com.lifepilot.agent.callback;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.observability.trace.TraceContext;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 迭代回调 — 抽象 LLM 调用方式（同步 / 流式）。
 *
 * <p>返回原始 ChatResponse（不自动执行 tool call），
 * 由 coreLoop 负责解析 tool call 并手动执行。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
@FunctionalInterface
public interface IterationCallback {

    /** 默认模型 ID。 */
    String DEFAULT_MODEL_ID = "ZhiWei";

    /**
     * 调用 LLM 并返回原始响应（不自动执行 tool call）。
     *
     * @param request       原始请求
     * @param messages      Spring AI 消息列表
     * @param toolCallbacks 工具回调列表（用于构建 tool definition，不自动执行）
     * @param traceContext  追踪上下文
     * @return LLM 原始响应（可能包含 tool call 请求）
     */
    ChatResponse callLlm(AgentRequest request,
                         List<Message> messages,
                         List<ToolCallback> toolCallbacks,
                         @Nullable TraceContext traceContext);

    /** 回调是否已在 callLlm 内部记录 LLM Trace 步骤（流式回调返回 true，避免 coreLoop 重复记录）。 */
    default boolean recordsLlmStep() { return false; }

    /** 获取本次调用的 Provider ID（用于 Trace 记录）。 */
    default String getProviderId() { return DEFAULT_MODEL_ID; }

    /** 获取本次调用的 Model ID（用于 Trace 记录）。 */
    default String getModelId() { return DEFAULT_MODEL_ID; }

    /**
     * 获取本次 LLM 调用产生的推理过程原文（DeepSeek V4 / Qwen3 等推理模型）。
     *
     * <p>用于多轮契约：DeepSeek 等推理模型要求带 tool_calls 的 assistant 消息
     * 必须在下一轮请求里回传 reasoning_content；ReactAgentLoop 调用 {@link #callLlm}
     * 后从此 getter 取值并写入对应 {@link com.lifepilot.agent.model.ReactStep.ToolCall}，
     * 由 ProviderMessageBuilder 在装载多轮 messages 时编码进 AssistantMessage，
     * 最终由请求体改写 filter 在请求出去前注入到 OpenAI 协议字段。
     *
     * <p>非推理模型或本次调用未产生 reasoning chunk 时返回空串。
     *
     * @return 推理过程原文；非推理路径返回空串
     */
    default String getFinalReasoningContent() { return ""; }
}
