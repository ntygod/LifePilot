package com.lifepilot.memory.governance.lifecycle.events;

import java.util.List;

/**
 * 主动引擎任务取消事件，携带该任务产出的 insight 实体 id 列表供级联清理。
 *
 * <p>{@code relatedInsightEntityIds} 在构造时做防御拷贝，保证事件在跨 listener
 * 异步消费时不会被外部修改。null 入参会立即抛 NullPointerException。
 *
 * @author zsg
 * @since 2026-04-23
 */
public record ProactiveTaskCancelled(
    String taskId,
    List<String> relatedInsightEntityIds
) {
    public ProactiveTaskCancelled {
        relatedInsightEntityIds = List.copyOf(relatedInsightEntityIds);
    }
}
