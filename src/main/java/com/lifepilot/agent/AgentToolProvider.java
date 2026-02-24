package com.lifepilot.agent;

import com.lifepilot.agent.model.AgentState;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具桥接层接口。
 *
 * <p>AgentLoop 通过此接口获取工具回调。
 * 本 spec 仅提供空实现，具体实现在工具系统 spec 中完成。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public interface AgentToolProvider {

    /**
     * 获取工具回调列表。
     *
     * @param state 当前 Agent 状态
     * @return 工具回调列表（Spring AI ToolCallback）
     */
    List<ToolCallback> getToolCallbacks(AgentState state);
}
