package com.lifepilot.llm.stream;

import org.springframework.lang.Nullable;

/**
 * 工具调用增量事件。
 *
 * <p>承载 tool_calls 数组某个 index 位置的增量片段。Provider 行为差异：
 * 部分 Provider 按 index 追加 arguments 片段，部分每个 chunk 返回当前累计 arguments。
 * 由消费方（{@code StreamingToolCallAggregator}）做归并。
 *
 * @param index          tool call 在数组中的位置
 * @param id             tool call ID（首次出现时携带，后续可空）
 * @param name           工具名（首次出现时携带，后续可空）
 * @param argumentsDelta 参数 JSON 片段
 * @author zsg
 * @since 2026-04-27
 */
public record ToolCallDelta(int index,
                            @Nullable String id,
                            @Nullable String name,
                            String argumentsDelta) implements LlmStreamEvent {
}
