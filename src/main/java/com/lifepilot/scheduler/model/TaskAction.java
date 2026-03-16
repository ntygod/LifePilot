package com.lifepilot.scheduler.model;

import com.lifepilot.notification.Urgency;

import java.util.Map;

/**
 * 定时任务触发后执行的动作。
 *
 * <p>使用 sealed interface 确保动作类型在编译时完全已知，
 * switch 表达式可穷举匹配。
 *
 * @author zsg
 * @since 2026-03-16
 */
public sealed interface TaskAction {

    /**
     * 发送通知动作。
     *
     * @param content 通知内容
     * @param urgency 紧急程度
     */
    record SendNotification(
            String content,
            Urgency urgency
    ) implements TaskAction {}

    /**
     * 调用 Agent 对话动作。
     *
     * @param message 对话内容
     */
    record InvokeAgent(
            String message
    ) implements TaskAction {}

    /**
     * 执行工具动作。
     *
     * @param toolId 工具 ID
     * @param params 工具输入参数
     */
    record ExecuteTool(
            String toolId,
            Map<String, Object> params
    ) implements TaskAction {}
}
