package com.lifepilot.interaction.cli;

import org.jline.reader.LineReader;

/**
 * 交互式对话命令 — 维持多轮对话会话。
 *
 * <p>调用 AgentLoop 处理用户消息，支持流式响应输出、
 * 会话管理（/new 新建、/exit 退出）和异常处理。</p>
 *
 * <p>注意：当前为骨架实现，完整逻辑将在后续任务中补充。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ChatCommand {

    /**
     * 启动对话会话。
     *
     * @param lineReader JLine LineReader
     * @param renderer 响应渲染器
     */
    public void startSession(LineReader lineReader, ResponseRenderer renderer) {
        // TODO: 后续任务实现完整对话逻辑
        throw new UnsupportedOperationException("ChatCommand 尚未实现");
    }
}
