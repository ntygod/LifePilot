package com.lifepilot.memory.lifecycle.events;

import java.util.List;

/**
 * 主动引擎任务取消事件，携带该任务产出的 insight 实体 id 列表供级联清理。
 *
 * @author zsg
 * @since 2026-04-23
 */
public record ProactiveTaskCancelled(
    String taskId,
    List<String> relatedInsightEntityIds
) {}
