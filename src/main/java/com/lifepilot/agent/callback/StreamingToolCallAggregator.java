package com.lifepilot.agent.callback;

import com.lifepilot.llm.stream.ToolCallDelta;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 tool call 增量聚合器。
 *
 * <p>兼容两类 Provider 增量模式：
 * 一类按 index 追加 arguments 片段；另一类每个 chunk 返回当前累计 arguments。
 * 聚合时优先按 index 归并，若后续 chunk 补齐 id，则继续保留同一槽位。</p>
 *
 * @author zsg
 * @since 2026-03-30
 */
final class StreamingToolCallAggregator {

    private final List<PartialToolCall> partialToolCalls = new ArrayList<>();

    void merge(List<AssistantMessage.ToolCall> toolCalls) {
        for (int i = 0; i < toolCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(i);
            int slot = resolveSlot(toolCall, i);
            while (partialToolCalls.size() <= slot) {
                partialToolCalls.add(new PartialToolCall());
            }
            partialToolCalls.get(slot).merge(toolCall);
        }
    }

    /**
     * 合并单个 {@link ToolCallDelta} 增量到聚合器。
     *
     * <p>相比 {@link #merge(List)}（处理 Spring AI 的 ChatResponse.AssistantMessage.ToolCall 列表），
     * 本方法承载 LlmStreamEvent 流的 ToolCallDelta 单个事件 — 由 OpenAiBase / Anthropic / Ollama
     * 等 Adapter 在 streamEvents 路径下发出。
     *
     * @param delta 工具调用增量事件
     */
    void merge(ToolCallDelta delta) {
        int slot = resolveSlotForDelta(delta);
        while (partialToolCalls.size() <= slot) {
            partialToolCalls.add(new PartialToolCall());
        }
        partialToolCalls.get(slot).mergeDelta(delta);
    }

    private int resolveSlotForDelta(ToolCallDelta delta) {
        // 优先按 id 匹配已有槽位
        if (hasText(delta.id())) {
            for (int i = 0; i < partialToolCalls.size(); i++) {
                if (delta.id().equals(partialToolCalls.get(i).id)) {
                    return i;
                }
            }
        }
        // 按 index 定位；若 index 槽位被不同 id 占用则追加新槽位
        if (delta.index() < partialToolCalls.size()) {
            PartialToolCall existing = partialToolCalls.get(delta.index());
            if (hasText(existing.id) && hasText(delta.id()) && !existing.id.equals(delta.id())) {
                return partialToolCalls.size();
            }
        }
        return delta.index();
    }

    boolean isEmpty() {
        return partialToolCalls.isEmpty();
    }

    List<AssistantMessage.ToolCall> toolCalls() {
        return partialToolCalls.stream()
                .map(PartialToolCall::toToolCall)
                .toList();
    }

    private int resolveSlot(AssistantMessage.ToolCall toolCall, int fallbackIndex) {
        if (hasText(toolCall.id())) {
            for (int i = 0; i < partialToolCalls.size(); i++) {
                if (toolCall.id().equals(partialToolCalls.get(i).id)) {
                    return i;
                }
            }
        }
        // 当 id 为空且 fallbackIndex 指向的槽位已被不同 id 的 tool call 占用时，追加新槽位
        if (fallbackIndex < partialToolCalls.size()) {
            PartialToolCall existing = partialToolCalls.get(fallbackIndex);
            if (hasText(existing.id) && !existing.id.equals(toolCall.id())) {
                return partialToolCalls.size();
            }
        }
        return fallbackIndex;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static String mergeArguments(String current, @Nullable String deltaOrSnapshot) {
        if (!hasText(deltaOrSnapshot)) {
            return current;
        }
        String next = deltaOrSnapshot;
        if (current.isEmpty()) {
            return next;
        }
        if (next.startsWith(current)) {
            return next;
        }
        if (current.endsWith(next)) {
            return current;
        }
        return current + next;
    }

    private static final class PartialToolCall {

        @Nullable
        private String id;

        @Nullable
        private String type;

        @Nullable
        private String name;

        private String arguments = "";

        private void merge(AssistantMessage.ToolCall toolCall) {
            if (hasText(toolCall.id())) {
                this.id = toolCall.id();
            }
            if (hasText(toolCall.type())) {
                this.type = toolCall.type();
            }
            if (hasText(toolCall.name())) {
                this.name = toolCall.name();
            }
            this.arguments = mergeArguments(this.arguments, toolCall.arguments());
        }

        /**
         * 合并 LlmStreamEvent 流的 ToolCallDelta 增量。
         *
         * <p>ToolCallDelta 没有 type 字段（OpenAI 协议固定为 "function"，无意义），
         * 序列化时由 {@link #toToolCall()} 默认填 "function"。
         */
        private void mergeDelta(ToolCallDelta delta) {
            if (hasText(delta.id())) {
                this.id = delta.id();
            }
            if (hasText(delta.name())) {
                this.name = delta.name();
            }
            this.arguments = mergeArguments(this.arguments, delta.argumentsDelta());
        }

        private AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(
                    id,
                    hasText(type) ? type : "function",
                    name != null ? name : "",
                    arguments
            );
        }
    }
}
