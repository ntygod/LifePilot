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

    /** 获取本次调用的 Provider ID（用于 Trace 记录）。 */
    default String getProviderId() { return DEFAULT_MODEL_ID; }

    /** 获取本次调用的 Model ID（用于 Trace 记录）。 */
    default String getModelId() { return DEFAULT_MODEL_ID; }
}
