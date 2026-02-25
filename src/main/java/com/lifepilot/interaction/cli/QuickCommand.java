package com.lifepilot.interaction.cli;

/**
 * 快捷命令分发器 — 直接调用各模块 API。
 *
 * <p>支持 todo/schedule/habit/llm/mcp/skill 快捷命令，
 * 通过 DynamicToolRegistry 和各模块注册中心执行操作。</p>
 *
 * <p>注意：当前为骨架实现，完整逻辑将在后续任务中补充。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class QuickCommand {

    /**
     * 执行快捷命令。
     *
     * @param command 命令名（todo/schedule/habit/llm/mcp/skill）
     * @param subArgs 子命令和参数
     * @param renderer 响应渲染器
     */
    public void execute(String command, String[] subArgs, ResponseRenderer renderer) {
        // TODO: 后续任务实现完整快捷命令逻辑
        throw new UnsupportedOperationException("QuickCommand 尚未实现");
    }
}
