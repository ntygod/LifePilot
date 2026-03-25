package com.lifepilot.agent;

import com.lifepilot.agent.model.ReactAgentState;
import jakarta.annotation.Nullable;
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
     * @param streamId SSE 流标识（用于精确推送授权审批请求，CLI 场景为 null）
     * @return 工具回调列表（Spring AI ToolCallback）
     */
    List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId);

    /**
     * 根据工具 ID 解析用户可读的显示名称。
     *
     * <p>用于前端展示场景（推理时间线、工具确认卡片等），
     * 将技术 ID（如 {@code builtin.todo.create}）转换为中文名称（如 "创建待办"）。</p>
     *
     * @param toolId 工具技术标识
     * @return 工具显示名称，未找到时返回 null（前端回退到 toolId）
     */
    @Nullable
    default String resolveToolDisplayName(String toolId) {
        return null;
    }
}
