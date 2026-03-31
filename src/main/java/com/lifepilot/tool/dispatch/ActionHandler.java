package com.lifepilot.tool.dispatch;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;

/**
 * action 路由处理器。
 *
 * <p>用于承接某个具体 action 的执行逻辑。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
@FunctionalInterface
public interface ActionHandler {

    /**
     * 处理具体 action。
     *
     * @param input 已通过 Schema 校验的工具输入
     * @return 结构化执行结果
     */
    ToolResult handle(ToolInput input);
}
