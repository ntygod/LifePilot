package com.lifepilot.agent;

import com.lifepilot.agent.model.AgentState;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 空实现，返回空列表。
 *
 * <p>在工具系统 spec 完成前作为占位实现。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class NoOpAgentToolProvider implements AgentToolProvider {

    @Override
    public List<ToolCallback> getToolCallbacks(AgentState state) {
        return List.of();
    }
}
