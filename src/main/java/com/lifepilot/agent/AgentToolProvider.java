package com.lifepilot.agent;

import com.lifepilot.agent.model.ReactAgentState;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具桥接层接口。
 *
 * <p>AgentLoop 通过此接口获取工具回调，将 ToolContract 转换为 Spring AI ToolCallback。
 * 默认实现为 {@code ToolBridgeAgentToolProvider}，通过 ToolExecutionPipeline 执行工具调用。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public interface AgentToolProvider {

    /**
     * 获取工具回调列表。
     *
     * @param state 当前 ReAct Agent 状态
     * @return 工具回调列表（Spring AI ToolCallback）
     */
    List<ToolCallback> getToolCallbacks(ReactAgentState state);
}
